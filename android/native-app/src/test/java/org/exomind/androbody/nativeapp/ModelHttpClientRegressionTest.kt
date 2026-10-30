package org.exomind.androbody.nativeapp

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.core.WireFailure
import org.exomind.androbody.nativeapp.diagnostics.*
import org.exomind.androbody.nativeapp.model.*
import org.exomind.androbody.nativeapp.model.endpoint.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Real production OkHttp + codec tests. No credentials, cloud, Android permissions or sleeps for success. */
class ModelHttpClientRegressionTest {
    private fun settings(server: MockWebServer, protocol: ModelProtocol = ModelProtocol.ANTHROPIC, prefix: String = "/anthropic",
        key: String = "fixture-secret", exact: Boolean = false) = ModelSettings(protocol, server.loopbackUrl(prefix).toString(), key, "fixture-model",
            allowInsecureLocal = true, endpointMode = if (exact) EndpointMode.EXACT else EndpointMode.AUTO)
    private fun request() = JSONObject().put("model", "fixture-model").put("max_tokens", 128).put("stream", false)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "private-prompt-sentinel")))
    private fun ok(protocol: ModelProtocol = ModelProtocol.ANTHROPIC): String = when(protocol) {
        ModelProtocol.ANTHROPIC -> """{"id":"msg-fixture","type":"message","role":"assistant","model":"fixture-model","content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
        ModelProtocol.OPENAI -> """{"id":"chatcmpl-fixture","object":"chat.completion","model":"fixture-model","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
    }
    private fun response(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun failure(block: suspend () -> Any): WireFailure {
        try { block(); error("expected failure") } catch (error: WireFailure) { return error }
    }

    @Test fun deepseekPrefixGetsV1AndLogsActualBodyAndHeaders() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(200,ok()))
            val events=CopyOnWriteArrayList<TraceEvent>()
            val payload=request()
            val reply=ModelHttpClient(traces=TraceSink{events+=it}).requestWithEvidence(settings(server).validate(),payload,
                context=RequestContext("parent-fixture","diagnostic"))
            val sent=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("/anthropic/v1/messages",sent.path)
            assertEquals("fixture-secret",sent.getHeader("x-api-key"))
            assertEquals("2023-06-01",sent.getHeader("anthropic-version"))
            val bytes=sent.body.readByteArray()
            assertEquals(payload.toString(),String(bytes,Charsets.UTF_8))
            val observed=events.single{it.stage=="request_body_observed"}
            assertEquals(sha256(bytes),observed.fields["sha256"])
            assertEquals("true",observed.fields["matchesSerialized"])
            assertEquals("http://${sent.getHeader("Host")}${sent.path}",events.single{it.stage=="request_headers_end"}.fields["url"])
            assertEquals(1,reply.attempts)
            assertTrue(events.all{it.requestId=="parent-fixture"})
            assertFalse(events.toString().contains("fixture-secret"))
            assertFalse(events.toString().contains("private-prompt-sentinel"))
        }
    }
    @Test fun route404FallsBackAndCachesButDoesNotRewriteConfiguredAddress() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(404,"""{"error":{"type":"not_found_error","message":"route not found"}}"""))
            server.enqueue(response(200,ok()));server.enqueue(response(200,ok()))
            val client=ModelHttpClient();val input=settings(server)
            val first=client.requestWithEvidence(input,request())
            val second=client.requestWithEvidence(input,request())
            assertEquals(2,first.attempts);assertEquals(1,second.attempts)
            assertEquals("/anthropic/v1/messages",server.takeRequest().path)
            assertEquals("/anthropic/messages",server.takeRequest().path)
            assertEquals("/anthropic/messages",server.takeRequest().path)
            assertTrue(input.baseUrl.endsWith("/anthropic"))
        }
    }
    @Test fun explicitEndpointIsFirstAndExactModeDoesNotGuess() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(404,"{}"))
            val error=failure{ModelHttpClient().request(settings(server,prefix="/custom/messages",exact=true),request())}
            assertEquals("UPSTREAM_ROUTE_NOT_FOUND",error.code)
            assertEquals(1,server.requestCount)
            assertEquals("/custom/messages",server.takeRequest().path)
        }
    }
    @Test fun authAndModelAndRateErrorsNeverFanOut() = runBlocking {
        for((code,body) in listOf(401 to "{}",403 to "{}",429 to "{}",503 to "{}",
            404 to """{"error":{"code":"model_not_found","message":"unknown model"}}""")) {
            loopbackServer().use { server ->
                server.enqueue(response(code,body))
                failure { ModelHttpClient().request(settings(server),request()) }
                assertEquals("status=$code",1,server.requestCount)
            }
        }
    }
    @Test fun method405CanTryNextPath() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(405,"{}"));server.enqueue(response(200,ok()))
            assertEquals(2,ModelHttpClient().requestWithEvidence(settings(server),request()).attempts)
        }
    }
    @Test fun html200IsNotConnectionSuccessAndDoesNotPoisonCache() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type","text/html").setBody("<html>login</html>"))
            val client=ModelHttpClient()
            assertEquals("UPSTREAM_INVALID_RESPONSE",failure{client.request(settings(server),request())}.code)
            assertEquals(1,server.requestCount)
            server.enqueue(response(200,ok()))
            assertEquals(1,client.requestWithEvidence(settings(server),request()).attempts)
            assertEquals("/anthropic/v1/messages",server.takeRequest().path)
            assertEquals("/anthropic/v1/messages",server.takeRequest().path)
        }
    }
    @Test fun redirectDoesNotForwardKeyEvenWithRedirectingInjectedClient() = runBlocking {
        loopbackServer().use { target -> loopbackServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",target.loopbackUrl("/collect")))
            failure { ModelHttpClient(http=OkHttpClient.Builder().followRedirects(true).build()).request(settings(server),request()) }
            assertEquals(1,server.requestCount);assertEquals(0,target.requestCount)
        } }
    }
    @Test fun countTokensIsIndependentFromGeneration() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(200,ok()));server.enqueue(response(404,"{}"));server.enqueue(response(404,"{}"));server.enqueue(response(200,ok()))
            val client=ModelHttpClient()
            client.request(settings(server),request())
            failure{client.request(settings(server),request().apply{remove("max_tokens");remove("stream")},countTokens=true)}
            assertEquals(1,client.requestWithEvidence(settings(server),request()).attempts)
            assertEquals("/anthropic/v1/messages",server.takeRequest().path)
            assertEquals("/anthropic/v1/messages/count_tokens",server.takeRequest().path)
            assertEquals("/anthropic/messages/count_tokens",server.takeRequest().path)
            assertEquals("/anthropic/v1/messages",server.takeRequest().path)
        }
    }
    @Test fun openAiUsesBearerAndCustomPrefix() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(200,ok(ModelProtocol.OPENAI)))
            ModelHttpClient().request(settings(server,ModelProtocol.OPENAI,"/tenant/v1"),request())
            val sent=server.takeRequest();assertEquals("/tenant/v1/chat/completions",sent.path)
            assertEquals("Bearer fixture-secret",sent.getHeader("Authorization"));assertNull(sent.getHeader("x-api-key"))
        }
    }
    @Test fun modelCatalogUsesReadOnlyGetAndProtocolSpecificAuthentication() = runBlocking {
        loopbackServer().use { server ->
            val events=CopyOnWriteArrayList<TraceEvent>()
            server.enqueue(response(200,"""{"object":"list","data":[{"id":"fixture-model"},{"id":"fixture-secret"}]}"""))
            val reply=ModelHttpClient(traces=TraceSink{events+=it}).listModels(
                settings(server,ModelProtocol.OPENAI,"/tenant/v1",key="fixture-secret"))
            val sent=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("GET",sent.method)
            assertEquals("/tenant/v1/models",sent.path)
            assertEquals("Bearer fixture-secret",sent.getHeader("Authorization"))
            assertNull(sent.getHeader("x-api-key"))
            assertEquals(0L,sent.body.size)
            assertEquals(listOf("fixture-model"),reply.models)
            assertFalse(events.toString().contains("fixture-secret"))
        }
    }
    @Test fun anthropicCatalogUsesApiKeyAndVersionHeadersWithoutAPrompt() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(200,"""{"data":[{"id":"claude-fixture"}]}"""))
            val reply=ModelHttpClient().listModels(settings(server,ModelProtocol.ANTHROPIC,"/anthropic/v1"))
            val sent=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("GET",sent.method)
            assertEquals("/anthropic/v1/models",sent.path)
            assertEquals("fixture-secret",sent.getHeader("x-api-key"))
            assertEquals("2023-06-01",sent.getHeader("anthropic-version"))
            assertNull(sent.getHeader("Authorization"))
            assertEquals(0L,sent.body.size)
            assertEquals(listOf("claude-fixture"),reply.models)
        }
    }
    @Test fun modelCatalogRetriesOnlySameOriginRouteMismatch() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(404,"""{"error":{"message":"model not found"}}"""))
            server.enqueue(response(200,"""{"data":[{"id":"fixture-model"}]}"""))
            val reply=ModelHttpClient().listModels(settings(server,ModelProtocol.ANTHROPIC,"/tenant"))
            assertEquals(2,reply.attempts)
            assertEquals("/tenant/v1/models",server.takeRequest(2,TimeUnit.SECONDS)!!.path)
            assertEquals("/tenant/models",server.takeRequest(2,TimeUnit.SECONDS)!!.path)
        }
    }
    @Test fun cancellationClosesTransportWithoutTryingAnotherCandidate() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(200,ok()).setBodyDelay(2,TimeUnit.SECONDS))
            val events=CopyOnWriteArrayList<TraceEvent>()
            val task=launch(Dispatchers.IO){ModelHttpClient(traces=TraceSink{events+=it}).request(settings(server),request())}
            assertNotNull(withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)})
            task.cancelAndJoin()
            assertEquals(1,server.requestCount)
            assertTrue(events.any{it.stage=="request_cancelled"})
            assertFalse(events.any{it.stage=="request_completed"})
        }
    }
    @Test fun errorEchoesAreRedactedButReasonIsRetained() = runBlocking {
        loopbackServer().use { server ->
            val events=CopyOnWriteArrayList<TraceEvent>()
            server.enqueue(response(400,"""{"error":{"type":"invalid_request_error","message":"bad max_tokens; private-prompt-sentinel; fixture-secret"}}"""))
            failure{ModelHttpClient(traces=TraceSink{events+=it}).request(settings(server),request())}
            assertFalse(events.toString().contains("private-prompt-sentinel"));assertFalse(events.toString().contains("fixture-secret"))
            assertTrue(events.any{it.fields["message"]?.contains("bad max_tokens")==true})
        }
    }
    @Test fun oversizedResponseIsBoundedAndDoesNotRetry() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(200,"x".repeat(ModelHttpClient.MAX_RESPONSE_BYTES+1)))
            assertEquals("UPSTREAM_RESPONSE_TOO_LARGE",failure{ModelHttpClient().request(settings(server),request())}.code)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun diagnosticShapeKeepsTokenBudgetsButNotSecretsOrPromptText() {
        val payload=request().put("api_key","secret").put("access_token","private-token")
            .put("usage",JSONObject().put("input_tokens",20).put("output_tokens",4))
        val view=JSONObject(JsonTraceView.structure(payload.toString().toByteArray(),listOf("secret")))
        assertEquals(128,view.getInt("max_tokens"))
        assertEquals(20,view.getJSONObject("usage").getInt("input_tokens"))
        assertEquals("<redacted>",view.getString("api_key"))
        assertFalse(view.toString().contains("private-prompt-sentinel"))
        assertFalse(view.toString().contains("private-token"))
    }
    @Test fun truncatedErrorDoesNotJustifyAnotherRequest() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(response(404,"x".repeat(ModelHttpClient.MAX_ERROR_BYTES+10)))
            assertEquals("UPSTREAM_INCOMPLETE_ERROR",failure{ModelHttpClient().request(settings(server),request())}.code)
            assertEquals(1,server.requestCount)
        }
    }

}
