package org.exomind.androbody.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.exomind.androbody.nativeapp.config.ModelProtocol
import org.exomind.androbody.nativeapp.config.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Two actual localhost HTTP hops: client -> production gateway -> production model transport.
 * No cloud connection, global ADB change, external app permission or real API key.
 */
class GatewayTransportE2ETest {
    @Test fun anthropicGatewayRoutesToolLoopCountTokensAndBufferedSse() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".verification"))
        val graph=context.graph
        val requests=CopyOnWriteArrayList<RecordedRequest>()
        loopbackServer().use { upstream ->
            upstream.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                requests+=request
                if(request.path?.endsWith("/count_tokens")==true) return response(200,"""{"input_tokens":12}""")
                if(request.path!="/proxy/messages") return response(404,"""{"error":{"type":"not_found_error","message":"route missing"}}""")
                val body=request.body.clone().readUtf8()
                return if(body.contains("tool_result") || body.contains("SSE fixture")) response(200,finalMessage)
                else response(200,"""{"id":"fixture-call","type":"message","role":"assistant","model":"fixture-model","content":[{"type":"tool_use","id":"gateway_battery","name":"am_battery_snapshot","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}""")
            }}
            graph.profiles.save(ModelSettings(ModelProtocol.ANTHROPIC,upstream.loopbackUrl("/proxy").toString(),"upstream-fixture-key","fixture-model",allowInsecureLocal=true))
            graph.preferences.update{it.copy(gatewayScopes=setOf("am_battery_snapshot"),triggersEnabled=false)}
            val token=graph.credentials.token()
            val serving=launch(Dispatchers.IO){graph.gateway.serve("gateway-test-owner")}
            try {
                withTimeout(5_000){while(graph.gateway.state.value.status!="running")delay(25)}
                post("/v1/messages","{}",null).use {assertEquals(401,it.code)}
                assertEquals(0,upstream.requestCount)
                val input="""{"model":"fixture-model","max_tokens":128,"messages":[{"role":"user","content":"read battery"}]}"""
                post("/v1/messages",input,token).use {reply ->
                    assertEquals(200,reply.code)
                    assertTrue(reply.body!!.string().contains("gateway fixture completed"))
                    assertEquals("2",reply.header("X-AndroMorph-Upstream-Rounds"))
                    assertEquals("1",reply.header("X-AndroMorph-Receipt-Count"))
                }
                assertEquals(listOf("/proxy/v1/messages","/proxy/messages","/proxy/messages"),requests.take(3).map{it.path})
                assertTrue(requests.take(3).all{it.getHeader("x-api-key")=="upstream-fixture-key"})
                assertTrue(graph.receipts.entries.value.any{it.tool=="am_battery_snapshot" && it.status=="COMPLETED"})
                post("/v1/messages/count_tokens",input,token).use {reply ->assertEquals(200,reply.code);assertEquals(12,JSONObject(reply.body!!.string()).getInt("input_tokens"))}
                post("/v1/messages","""{"model":"fixture-model","max_tokens":128,"stream":true,"messages":[{"role":"user","content":"SSE fixture"}]}""",token).use {reply ->
                    assertEquals(200,reply.code)
                    val stream=reply.body!!.string()
                    assertTrue(stream.contains("event: message_start"));assertTrue(stream.contains("event: message_stop"))
                }
                val report=graph.networkDiagnostics.export()
                assertFalse(report.toString().contains(token));assertFalse(report.toString().contains("upstream-fixture-key"))
                assertTrue(graph.networkDiagnostics.events.value.any{it.origin=="gateway" && it.stage=="request_completed"})
                File(context.filesDir,"qa/evidence").apply{mkdirs()}.resolve("gateway-http-chain.json").writeText(report.toString(2))
                val received = JSONArray(requests.map { request ->
                    val bytes = request.body.clone().readByteArray()
                    JSONObject().put("method", request.method).put("path", request.path)
                        .put("receivedHost", request.getHeader("Host"))
                        .put("fixtureAuthenticationMatches", request.getHeader("x-api-key") == "upstream-fixture-key")
                        .put("bodyBytes", bytes.size).put("bodySha256", org.exomind.androbody.nativeapp.diagnostics.sha256(bytes))
                        .put("upstreamStreamRequested", JSONObject(String(bytes, Charsets.UTF_8)).optBoolean("stream", false))
                })
                File(context.filesDir,"qa/evidence/gateway-server-witness.json").writeText(received.toString(2))
            } finally {graph.gateway.stop("gateway-test-owner");serving.cancelAndJoin();graph.preferences.update{it.copy(gatewayScopes=emptySet())}}
        }
    }

    @Test fun openAiGatewayPreservesBearerAndActualEndpoint() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".verification"))
        val graph=context.graph
        loopbackServer().use {upstream ->
            upstream.enqueue(response(200,"""{"id":"chatcmpl-fixture","object":"chat.completion","model":"fixture-model","choices":[{"index":0,"message":{"role":"assistant","content":"OpenAI fixture"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""))
            graph.profiles.save(ModelSettings(ModelProtocol.OPENAI,upstream.loopbackUrl("/tenant/v1").toString(),"openai-fixture-key","fixture-model",allowInsecureLocal=true))
            graph.preferences.update{it.copy(gatewayScopes=emptySet())}
            val token=graph.credentials.token()
            val serving=launch(Dispatchers.IO){graph.gateway.serve("openai-test-owner")}
            try {
                withTimeout(5_000){while(graph.gateway.state.value.status!="running")delay(25)}
                post("/v1/chat/completions","""{"model":"fixture-model","messages":[{"role":"user","content":"hello"}]}""",token).use {reply ->assertEquals(200,reply.code);assertTrue(reply.body!!.string().contains("OpenAI fixture"))}
                val sent=upstream.takeRequest(2,TimeUnit.SECONDS)!!
                assertEquals("/tenant/v1/chat/completions",sent.path)
                assertEquals("Bearer openai-fixture-key",sent.getHeader("Authorization"))
                assertNull(sent.getHeader("x-api-key"))
            } finally {graph.gateway.stop("openai-test-owner");serving.cancelAndJoin()}
        }
    }
    private fun post(path:String,body:String,token:String?) = OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).build()
        .newCall(Request.Builder().url("http://127.0.0.1:8787$path").post(body.toRequestBody("application/json".toMediaType()))
            .apply{if(token!=null)header("x-api-key",token)}.build()).execute()
    private fun response(status:Int,body:String)=MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
    private val finalMessage="""{"id":"fixture-final","type":"message","role":"assistant","model":"fixture-model","content":[{"type":"text","text":"gateway fixture completed"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
}
