package org.exomind.androbody.nativeapp

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.exomind.androbody.nativeapp.config.ModelProtocol
import org.exomind.androbody.nativeapp.diagnostics.DiagnosticStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real MainActivity, settings, HTTP stack, Keystore, native agent, BatteryManager and diagnostic store.
 * Runs only in .verification. No direct graph.native.send call is used as a substitute for the UI.
 */
class ModelConnectionE2ETest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".verification")) { "Refusing to mutate a user/debug profile; build -PtestBuildType=verification" }
        val expectedRevision = InstrumentationRegistry.getArguments().getString("expectedSourceRevision")
        check(expectedRevision != null && BuildConfig.SOURCE_REVISION == expectedRevision) {
            "Installed verification APK source revision does not match the requested test input"
        }
    }
    @Test fun configureDraftProbeSaveSendToolRoundAndExportEvidence() {
        val context=app()
        val captured=CopyOnWriteArrayList<RecordedRequest>()
        loopbackServer().use { server ->
            server.dispatcher=object:Dispatcher(){ override fun dispatch(request:RecordedRequest):MockResponse {
                captured+=request
                if(request.method=="GET" && request.path=="/anthropic/v1/models") {
                    if(request.getHeader("x-api-key")!="fixture-key-no-real-secret" || request.getHeader("anthropic-version")!="2023-06-01")
                        return json(401,"""{"error":{"type":"authentication_error","message":"invalid fixture auth"}}""")
                    return json(200,"""{"data":[{"id":"fixture-model"}]}""")
                }
                if(request.path!="/anthropic/messages") return json(404,"""{"error":{"type":"not_found_error","message":"fixture route missing"}}""")
                val body=JSONObject(request.body.clone().readUtf8())
                val messages=body.getJSONArray("messages").toString()
                return if(messages.contains("Connection test")) json(200,message("OK"))
                    else if(!body.optBoolean("stream")) json(500,"""{"error":{"message":"native agent must stream"}}""")
                    else if(messages.contains("tool_result")) markdownStream("**设备电池**\n\n- 真实回执已完成\n- 此文本由离线夹具返回。")
                    else toolStream()
            }}
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("nav-SETTINGS").performClick()
                compose.waitUntil(5_000){compose.onAllNodesWithTag("model-address").fetchSemanticsNodes().any{node -> !node.config.contains(SemanticsProperties.Disabled)}}
                compose.onNodeWithTag("model-address").performScrollTo().performTextReplacement(server.loopbackUrl("/anthropic").toString())
                compose.onNodeWithTag("model-key").performScrollTo().performTextReplacement("fixture-key-no-real-secret")
                compose.onNodeWithTag("model-name").performScrollTo().performTextReplacement("fixture-model")
                compose.onNodeWithContentDescription("允许本地 HTTP 上游").performScrollTo().performClick()
                compose.onNodeWithTag("model-catalog-probe").performScrollTo().performClick()
                compose.waitUntil(10_000){compose.onAllNodesWithText("协议响应有效").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("model-option-ANTHROPIC-fixture-model").performScrollTo().performClick()
                compose.onNodeWithTag("model-test").performScrollTo().performClick()
                compose.onNodeWithTag("model-test-confirm").performClick()
                compose.waitUntil(20_000){context.graph.networkDiagnostics.events.value.any{it.stage=="request_completed" && it.origin=="diagnostic"}}
                assertEquals("GET",captured[0].method)
                assertEquals("/anthropic/v1/models",captured[0].path)
                assertEquals("GET",captured[1].method)
                assertEquals("/anthropic/v1/models",captured[1].path)
                assertEquals("/anthropic/v1/messages",captured[2].path)
                assertEquals("/anthropic/messages",captured[3].path)
                compose.onNodeWithTag("model-save").performScrollTo().performClick()
                compose.onNodeWithText("保存",substring=false).performClick()
                compose.waitUntil(5_000){context.graph.profiles.read(ModelProtocol.ANTHROPIC)?.model=="fixture-model"}
                compose.waitUntil(30_000){!context.graph.native.state.value.initializing}
                compose.onNodeWithTag("nav-ASSISTANT").performClick()
                compose.onNodeWithTag("assistant-composer").performTextReplacement("读取电池并给出来源，只依据真实回执。")
                compose.onNodeWithTag("assistant-composer").assertTextContains("读取电池并给出来源", substring = true)
                val out=File(context.filesDir,"qa/evidence").apply{mkdirs()}
                val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                device.takeScreenshot(File(out,"assistant-before-send.png"))
                val beforeSend=context.graph.native.state.value
                compose.onNodeWithTag("assistant-send").assertIsEnabled().assertIsDisplayed().performClick()
                val afterClick=context.graph.native.state.value
                File(out,"assistant-send-state.json").writeText(JSONObject().put("before",JSONObject()
                    .put("running",beforeSend.running).put("interrupted",beforeSend.interrupted)
                    .put("phase",beforeSend.phase))
                    .put("afterClick",JSONObject().put("running",afterClick.running)
                        .put("interrupted",afterClick.interrupted).put("phase",afterClick.phase)
                        .put("errorPresent",afterClick.error != null).put("errorLength",afterClick.error?.length ?: 0)
                        .put("entryCount",afterClick.entries.size).put("liveReplyLength",afterClick.liveReply?.length ?: 0))
                    .toString(2))
                device.takeScreenshot(File(out,"assistant-after-send-click.png"))
                assertTrue("Send action should enter the native task state; phase=${context.graph.native.state.value.phase}; error=${context.graph.native.state.value.error}",
                    context.graph.native.state.value.running)
                compose.waitUntil(20_000){context.graph.native.state.value.liveReply?.contains("设备电池")==true || context.graph.native.state.value.error != null}
                assertTrue(context.graph.native.state.value.liveReply.orEmpty().contains("设备电池"))
                compose.waitUntil(25_000){context.graph.native.state.value.entries.any{e->e.role=="assistant" && e.text.contains("设备电池")}}
                assertFalse(context.graph.native.state.value.interrupted)
                compose.onNodeWithText("设备电池").assertExists()
                compose.onNodeWithText("请求依据").assertDoesNotExist()
                compose.onNodeWithText("设备电池").performTouchInput { longClick() }
                compose.onNodeWithText("请求依据").assertExists()
                compose.onNodeWithText("请求依据").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("请求标识", substring = true).fetchSemanticsNodes().isNotEmpty() }
                androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    .takeScreenshot(File(out,"assistant-stream-markdown.png"))
                val receipt=context.graph.receipts.entries.value.last{e->e.tool=="am_battery_snapshot"}
                assertEquals("COMPLETED",receipt.status)
                val toolResponse=captured.last().body.clone().readUtf8()
                val observation=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!
                val level=observation.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)
                val scale=observation.getIntExtra(BatteryManager.EXTRA_SCALE,-1)
                val toolMessages=JSONObject(toolResponse).getJSONArray("messages")
                var measured: Double?=null
                for(i in 0 until toolMessages.length()) {
                    val blocks=toolMessages.getJSONObject(i).optJSONArray("content") ?: continue
                    for(j in 0 until blocks.length()) {
                        val block=blocks.optJSONObject(j) ?: continue
                        if(block.optString("type")=="tool_result") {
                            val result=JSONObject(block.getString("content"))
                            measured=result.getJSONObject("data").optDouble("levelPercent",Double.NaN)
                        }
                    }
                }
                assertNotNull(measured);assertTrue(level>=0 && scale>0)
                // Read-only emulator property; no fabricated zero or model text is the oracle.
                assertEquals(100.0*level/scale,measured!!,1.0)
                val trace=context.graph.networkDiagnostics.export()
                val serialized=trace.toString()
                assertFalse(serialized.contains("fixture-key-no-real-secret"))
                assertFalse(serialized.contains("读取电池并给出来源"))
                val taskEvents=context.graph.networkDiagnostics.events.value.filter{e->e.requestId==receipt.requestId}
                assertEquals(2,taskEvents.count{e->e.stage=="request_completed"})
                assertTrue(taskEvents.any{e->e.stage=="request_body_observed" && e.fields["matchesSerialized"]=="true"})
                device.pressBack()
                compose.waitUntil(5_000){compose.onAllNodesWithTag("nav-RECORDS").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("nav-RECORDS").performClick()
                compose.onNodeWithText("网络", substring = false).performClick()
                compose.onNodeWithText("模型请求诊断").assertIsDisplayed()
                androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    .takeScreenshot(File(out,"native-network-chain.png"))
                File(out,"ui-full-chain.json").writeText(trace.toString(2))
                val serverEvidence = JSONArray(captured.mapIndexed { index, received ->
                    val bytes = received.body.clone().readByteArray()
                    JSONObject().put("sequence", index + 1).put("method", received.method)
                        .put("urlFromReceivedHostAndPath", "http://${received.getHeader("Host")}${received.path}")
                        .put("authenticationScheme", if (received.getHeader("x-api-key") != null) "x-api-key" else if (received.getHeader("Authorization") != null) "bearer" else "none")
                        .put("fixtureKeyHeaderMatches",
                            received.getHeader("x-api-key") == "fixture-key-no-real-secret" ||
                                received.getHeader("Authorization") == "Bearer fixture-key-no-real-secret")
                        .put("anthropicVersion", received.getHeader("anthropic-version"))
                        .put("bodyBytes", bytes.size).put("bodySha256", org.exomind.androbody.nativeapp.diagnostics.sha256(bytes))
                })
                File(out,"request-full-chain.json").writeText(JSONObject().put("scope", "offline fixture; independent server witness plus Android observation")
                    .put("clientDiagnostic", trace).put("serverReceived", serverEvidence)
                    .put("batteryOracle", JSONObject().put("level", level).put("scale", scale).put("toolResultPercent", measured))
                    .put("toolReceipts", context.graph.receipts.snapshot()).put("requestId", receipt.requestId).toString(2))
                File(out,"ui-full-chain-result.json").writeText(JSONObject().put("status","PASS")
                    .put("scope","verification APK MainActivity UI -> read-only protocol/model catalogue -> explicit test/save -> native SSE tool round -> real BatteryManager -> Markdown UI + private diagnostic export")
                    .put("safPicker","NOT_RUN_BY_THIS_TEST").put("fixtureRequests",captured.size)
                    .put("receiptId",receipt.id).put("requestId",receipt.requestId).toString(2))
            }
        }
    }
    private fun toolStream() = sseResponse(
        JSONObject().put("type","message_start").put("message",JSONObject().put("id","fixture-stream-tool")
            .put("role","assistant").put("model","fixture-model").put("content",JSONArray()).put("usage",JSONObject().put("input_tokens",2))),
        JSONObject().put("type","content_block_start").put("index",0).put("content_block",JSONObject()
            .put("type","tool_use").put("id","fixture_battery_1").put("name","am_battery_snapshot").put("input",JSONObject())),
        JSONObject().put("type","content_block_delta").put("index",0).put("delta",JSONObject()
            .put("type","input_json_delta").put("partial_json","{}")),
        JSONObject().put("type","content_block_stop").put("index",0),
        JSONObject().put("type","message_delta").put("delta",JSONObject().put("stop_reason","tool_use")
            .put("stop_sequence",JSONObject.NULL)).put("usage",JSONObject().put("output_tokens",2)),
        JSONObject().put("type","message_stop"),
    )

    private fun markdownStream(text:String) = sseResponse(
        JSONObject().put("type","message_start").put("message",JSONObject().put("id","fixture-stream-final")
            .put("role","assistant").put("model","fixture-model").put("content",JSONArray()).put("usage",JSONObject().put("input_tokens",2))),
        JSONObject().put("type","content_block_start").put("index",0).put("content_block",JSONObject().put("type","text").put("text","")),
        JSONObject().put("type","content_block_delta").put("index",0).put("delta",JSONObject().put("type","text_delta").put("text",text)),
        JSONObject().put("type","content_block_stop").put("index",0),
        JSONObject().put("type","message_delta").put("delta",JSONObject().put("stop_reason","end_turn")
            .put("stop_sequence",JSONObject.NULL)).put("usage",JSONObject().put("output_tokens",8)),
        JSONObject().put("type","message_stop"),
        throttle = true,
    )

    private fun sseResponse(vararg events:JSONObject, throttle:Boolean=false):MockResponse {
        val body=events.joinToString("") { event ->
            "event: " + event.getString("type") + "\ndata: " + event.toString() + "\n\n"
        }
        val response=MockResponse().setResponseCode(200).setHeader("Content-Type","text/event-stream; charset=utf-8")
            .setChunkedBody(body,32)
        if(throttle) response.throttleBody(24,60,java.util.concurrent.TimeUnit.MILLISECONDS)
        return response
    }
    @Test fun pathFailureDoesNotLockNativeContextAndCanBeRetried() {
        val context=app()
        loopbackServer().use { server ->
            server.enqueue(json(404,"{}"));server.enqueue(json(404,"{}"));server.enqueue(markdownStream("corrected"))
            runBlocking {
                context.graph.profiles.save(org.exomind.androbody.nativeapp.config.ModelSettings(ModelProtocol.ANTHROPIC,
                    server.loopbackUrl("/broken").toString(),"fixture-key","fixture-model",allowInsecureLocal=true))
                context.graph.preferences.update{it.copy(protocol=ModelProtocol.ANTHROPIC)}
                context.graph.native.state.first { !it.initializing }
                context.graph.native.freshContext()
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("assistant-composer").performTextReplacement("first")
                compose.onNodeWithTag("assistant-send").performClick()
                compose.waitUntil(20_000){context.graph.native.state.value.error!=null && !context.graph.native.state.value.running}
                assertFalse(context.graph.native.state.value.interrupted)
                val requestsBeforeRetry = server.requestCount
                compose.onNodeWithTag("assistant-composer").performTextReplacement("retry after correction")
                compose.onNodeWithTag("assistant-send").performClick()
                compose.waitUntil(20_000) { server.requestCount > requestsBeforeRetry ||
                    (!context.graph.native.state.value.running && context.graph.native.state.value.error != null) }
                val retryState = context.graph.native.state.value
                File(context.filesDir, "qa/evidence").apply { mkdirs() }
                File(context.filesDir, "qa/evidence/recovery-retry-state.json").writeText(JSONObject()
                    .put("requestsBeforeRetry", requestsBeforeRetry).put("requestsAfterRetry", server.requestCount)
                    .put("phase", retryState.phase).put("running", retryState.running)
                    .put("initializing", retryState.initializing).put("interrupted", retryState.interrupted)
                    .put("errorCode", retryState.error?.substringBefore('：'))
                    .put("entryRoles", JSONArray(retryState.entries.map { it.role }))
                    .put("entryRequestIds", JSONArray(retryState.entries.map { it.requestId ?: "" }))
                    .toString(2))
                assertTrue("Retry did not reach the MockWebServer; state=${retryState.phase}, running=${retryState.running}, error=${retryState.error?.substringBefore('：')}",
                    server.requestCount > requestsBeforeRetry)
                compose.waitUntil(20_000){context.graph.native.state.value.entries.any{e->e.text=="corrected"}}
                assertFalse(context.graph.native.state.value.interrupted)
            }
        }
    }
    private fun json(code:Int,body:String)=MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)
    private fun message(text:String)=JSONObject().put("id","fixture-final").put("type","message").put("role","assistant")
        .put("model","fixture-model").put("content",JSONArray().put(JSONObject().put("type","text").put("text",text)))
        .put("stop_reason","end_turn").put("usage",JSONObject().put("input_tokens",2).put("output_tokens",2)).toString()
}
