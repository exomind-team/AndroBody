package org.exomind.androbody.nativeapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.storage.NetworkLogEntry
import okhttp3.mockwebserver.MockResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Exercises retained local controls together with the merged production settings screen. */
class MergedUiBoundaryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".verification"))
    }
    @Test fun nativeToolPermissionsCanAllowAskAndDenyPerCapability() {
        val context = context()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("nav-BODY").performClick()
            compose.onNodeWithTag("native-approval-ALLOW_ALL").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                context.graph.preferences.state.value.nativeApprovalMode == NativeApprovalMode.ALLOW_ALL
            }
            assertTrue(context.graph.preferences.state.value.nativeCapabilityChoices.isEmpty())

            val tts = "native-capability-am_tts_speak"
            compose.onNodeWithTag("body-list").performScrollToNode(hasText("朗读文本", substring = false))
            compose.onNodeWithTag(tts).performClick()
            compose.onNodeWithText("拒绝", substring = false).performClick()
            compose.waitUntil(5_000) {
                context.graph.preferences.state.value.nativeCapabilityChoices["am_tts_speak"] == NativeCapabilityChoice.DENY
            }

            compose.onNodeWithTag(tts).performClick()
            compose.onNodeWithText("每次询问", substring = false).performClick()
            compose.waitUntil(5_000) {
                context.graph.preferences.state.value.nativeCapabilityChoices["am_tts_speak"] == NativeCapabilityChoice.ASK
            }

            compose.onNodeWithTag(tts).performClick()
            compose.onNodeWithText("始终允许", substring = false).performClick()
            compose.waitUntil(5_000) {
                context.graph.preferences.state.value.nativeCapabilityChoices["am_tts_speak"] == NativeCapabilityChoice.ALLOW
            }
            assertEquals(NativeApprovalMode.ALLOW_ALL, context.graph.preferences.state.value.nativeApprovalMode)
        }
    }

    @Test fun changedDestinationCannotProbeOrSaveWithBlankStoredKey() {
        val context = context()
        loopbackServer().use { original -> loopbackServer().use { other ->
            val saved = ModelSettings(ModelProtocol.ANTHROPIC, original.loopbackUrl("/anthropic").toString(),
                "fixture-stored-key", "fixture", allowInsecureLocal = true)
            context.graph.profiles.save(saved)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("nav-SETTINGS").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("model-address").fetchSemanticsNodes().any {
                    node -> !node.config.contains(SemanticsProperties.Disabled)
                } }
                compose.onNodeWithTag("model-address").performScrollTo().performTextReplacement(other.loopbackUrl("/anthropic").toString())
                compose.onNodeWithTag("model-test").performScrollTo().performClick()
                compose.onNodeWithTag("model-test-confirm").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("目的地或连接规则已改变", substring = true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(0, original.requestCount); assertEquals(0, other.requestCount)
                compose.onNodeWithTag("model-save").performScrollTo().performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("目的地或连接规则已改变", substring = true).fetchSemanticsNodes().size >= 2 }
                assertEquals(saved.baseUrl, context.graph.profiles.read(ModelProtocol.ANTHROPIC)!!.baseUrl)
                assertEquals(0, other.requestCount)
            }
        } }
    }

    @Test fun editingAwayAndBackCancelsProbeAndCannotRestoreItsSuccessBadge() {
        val context = context()
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS)
                .setBody("""{"content":[],"stop_reason":"end_turn"}"""))
            val saved = ModelSettings(ModelProtocol.ANTHROPIC, server.loopbackUrl("/anthropic").toString(),
                "fixture-key", "fixture", allowInsecureLocal = true)
            context.graph.profiles.save(saved)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("nav-SETTINGS").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("model-address").fetchSemanticsNodes().any {
                    node -> !node.config.contains(SemanticsProperties.Disabled)
                } }
                compose.onNodeWithTag("model-test").performScrollTo().performClick()
                compose.onNodeWithTag("model-test-confirm").performClick()
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                compose.onNodeWithTag("model-name").performScrollTo().performTextReplacement("changed")
                compose.onNodeWithTag("model-name").performTextReplacement("fixture")
                compose.waitUntil(5_000) { context.graph.networkDiagnostics.events.value.any { it.stage == "request_cancelled" } }
                compose.onAllNodesWithText("连接与协议核验通过", substring = true).assertCountEquals(0)
                assertEquals(1, server.requestCount)
                assertEquals("fixture", context.graph.profiles.read(ModelProtocol.ANTHROPIC)!!.model)
            }
        }
    }

    @Test fun legacyLogAndExplicitClearRemainAvailableWithoutErasingEvidence() {
        val context = context()
        val graph = context.graph
        runBlocking { graph.native.state.first { !it.initializing } }
        val sessionId = requireNotNull(graph.native.state.value.currentSessionId)
        graph.nativeSessions.save(sessionId, JSONObject().put("schema", 3).put("running", false)
            .put("mayHaveDeviceEffects", false).put("history", JSONArray())
            .put("entries", JSONArray().put(JSONObject().put("id", "clear-fixture-user")
                .put("role", "user").put("text", "待清理的隔离测试消息")
                .put("at", System.currentTimeMillis()))))
        graph.native.switchSession(sessionId)
        context.graph.networkLogs.append(NetworkLogEntry("legacy-fixture", "legacy-task", "Anthropic",
            "https://fixture.invalid/messages", "FAILED", 404, 10, "fixture", "UPSTREAM_HTTP_404", 1))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { graph.native.state.value.entries.isNotEmpty() }
            compose.onNodeWithText("清空", substring = false).performClick()
            compose.onNodeWithText("确认清空").performClick()
            val persisted = graph.nativeSessions.open()
            assertFalse(persisted.interrupted)
            assertEquals(0, persisted.entries.size)
            compose.onNodeWithTag("nav-RECORDS").performClick()
            compose.onNodeWithText("网络", substring = false).performClick()
            compose.onNodeWithText("查看旧版日志").performClick()
            compose.onNodeWithText("一键复制", substring = false).assertIsDisplayed()
            assertEquals("legacy-task", context.graph.networkLogs.entries.value.single().requestId)
            compose.onNodeWithText("返回当前网络诊断").performClick()
            compose.onNodeWithText("模型请求诊断").assertIsDisplayed()
        }
    }
}
