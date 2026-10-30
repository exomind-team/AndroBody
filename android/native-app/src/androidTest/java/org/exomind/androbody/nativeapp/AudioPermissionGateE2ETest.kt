package org.exomind.androbody.nativeapp

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.exomind.androbody.nativeapp.config.NativeApprovalMode
import org.exomind.androbody.nativeapp.core.Invocation
import org.exomind.androbody.nativeapp.core.Origin
import org.exomind.androbody.nativeapp.core.Outcome
import org.exomind.androbody.nativeapp.core.Principal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Starts with RECORD_AUDIO denied; permission state is prepared before instrumentation launches the target Activity. */
class AudioPermissionGateE2ETest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun missingSystemMicrophoneGrantIsVisibleAndPreventsRecorderStart(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".verification"))
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val graph = context.graph
        graph.preferences.update { current -> current.copy(nativeApprovalMode = NativeApprovalMode.ALLOW_ALL) }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("nav-BODY").performClick()
            withTimeout(10_000) { while (!graph.uiVisible) delay(50) }
            compose.onNodeWithTag("body-list").performScrollToNode(hasTestTag("audio-request-permission"))
            compose.onNodeWithTag("audio-request-permission").assertExists()

            val receipt = graph.broker.invoke(Invocation(
                requestId = "audio-permission-denied-fixture",
                callId = "audio-permission-denied-fixture",
                principal = Principal("native:user", Origin.NATIVE),
                capability = "am_audio_recording_start",
                arguments = JSONObject().put("maxDurationSeconds", 10),
            ))
            assertEquals(Outcome.FAILED, receipt.outcome)
            assertTrue(receipt.evidence.contains("NEEDS_GRANT"))
            assertEquals("idle", graph.audioRecording.state.value.phase)
            assertTrue(graph.audioRecordings.items.value.isEmpty())
        }
    }
}
