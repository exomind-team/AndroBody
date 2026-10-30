package org.exomind.androbody.nativeapp

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Sends into a pending session with no configured model; no provider or device tool may run. */
class RecoverySendE2ETest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun appContext() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".verification"))
    }

    private fun seedPendingSession(context: android.content.Context, requestId: String?) {
        val graph = context.graph
        runBlocking { graph.native.state.first { !it.initializing } }
        graph.native.newSession()
        val sessionId = requireNotNull(graph.native.state.value.currentSessionId)
        val user = JSONObject().put("id", "pending-user-${requestId ?: "unknown"}").put("role", "user")
            .put("text", "只读核对这条测试请求").put("at", System.currentTimeMillis())
        if (requestId != null) user.put("requestId", requestId)
        val document = JSONObject().put("schema", 3).put("running", false)
            .put("mayHaveDeviceEffects", true).put("history", JSONArray())
            .put("entries", JSONArray().put(user))
        if (requestId != null) document.put("unresolvedRequestId", requestId)
        graph.nativeSessions.save(sessionId, document)
        graph.native.switchSession(sessionId)
        assertTrue(graph.native.state.value.interrupted)
        assertEquals(requestId, graph.native.state.value.unresolvedRequestId)
    }

    private fun enterRecoveryMessage(prompt: String) {
        val context = appContext()
        val graph = context.graph
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("assistant-composer").performTextInput(prompt)
            compose.onNodeWithTag("assistant-send").assertIsEnabled().performClick()
            compose.waitUntil(10_000) {
                !graph.native.state.value.running && graph.native.state.value.error != null
            }
        }
        assertEquals("MODEL_REQUIRED", graph.native.state.value.error?.substringBefore('：'))
        assertTrue("A local missing-model error must leave the unknown device outcome locked", graph.native.state.value.interrupted)
        assertFalse("No upstream transport should run without a configured model", graph.networkDiagnostics.events.value.any {
            it.subsystem == "transport" && it.origin == "native"
        })
    }

    @Test fun interruptedSessionAcceptsReadOnlyRecoveryMessageWithKnownRequestId() {
        val context = appContext()
        seedPendingSession(context, "pending-recovery-fixture")
        enterRecoveryMessage("继续")
        assertEquals("pending-recovery-fixture", context.graph.native.state.value.unresolvedRequestId)
    }
}
