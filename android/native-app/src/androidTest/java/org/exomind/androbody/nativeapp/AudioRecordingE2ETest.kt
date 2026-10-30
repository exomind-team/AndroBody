package org.exomind.androbody.nativeapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.exomind.androbody.nativeapp.body.AudioRecordingStore
import org.exomind.androbody.nativeapp.config.NativeApprovalMode
import org.exomind.androbody.nativeapp.core.BodyFailure
import org.exomind.androbody.nativeapp.core.Invocation
import org.exomind.androbody.nativeapp.core.Origin
import org.exomind.androbody.nativeapp.core.Outcome
import org.exomind.androbody.nativeapp.core.Principal
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Short emulator-only microphone recordings; audio bytes are never printed or uploaded. */
class AudioRecordingE2ETest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".verification"))
    }

    private suspend fun allowStartTool(context: android.content.Context) {
        val graph = context.graph
        graph.preferences.update { current -> current.copy(
            nativeApprovalMode = NativeApprovalMode.ALLOW_ALL,
            nativeCapabilityChoices = current.nativeCapabilityChoices + ("am_audio_recording_start" to
                org.exomind.androbody.nativeapp.config.NativeCapabilityChoice.ALLOW),
        ) }
    }

    private fun invocation(capability: String, args: JSONObject = JSONObject()) = Invocation(
        requestId = "audio-e2e-${System.nanoTime()}", callId = "audio-e2e-$capability",
        principal = Principal("native:user", Origin.NATIVE), capability = capability, arguments = args,
    )

    private fun saveArtifactEvidence(context: android.content.Context, artifact: org.exomind.androbody.nativeapp.body.AudioRecordingArtifact) {
        val directory = File(context.filesDir, "qa/evidence").apply { mkdirs() }
        File(directory, "audio-recording-${artifact.id}.json").writeText(JSONObject()
            .put("scope", "emulator recording metadata only; raw audio is not exported or logged")
            .put("artifact", artifact.toJson()).toString(2))
    }

    private fun assertReadableContainer(
        context: android.content.Context,
        artifact: org.exomind.androbody.nativeapp.body.AudioRecordingArtifact,
    ) {
        assertTrue(artifact.audioMimeType?.startsWith("audio/") == true)
        assertTrue((artifact.audioSampleRateHz ?: 0) > 0)
        assertTrue((artifact.audioChannelCount ?: 0) > 0)
        val expectedDurationMs = requireNotNull(artifact.containerDurationMs)
        assertTrue(expectedDurationMs >= 1_000L)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context.graph.audioRecordings.fileForExport(artifact.id).absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            assertNotNull("published M4A has a readable audio track", trackIndex)
            val format = extractor.getTrackFormat(requireNotNull(trackIndex))
            assertEquals(artifact.audioMimeType, format.getString(MediaFormat.KEY_MIME))
            assertEquals(expectedDurationMs, format.getLong(MediaFormat.KEY_DURATION) / 1_000L)
            assertEquals(artifact.audioSampleRateHz, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(artifact.audioChannelCount, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            extractor.selectTrack(requireNotNull(trackIndex))
            assertTrue("published M4A has readable encoded audio samples",
                extractor.readSampleData(java.nio.ByteBuffer.allocate(64 * 1_024), 0) > 0)
        } finally {
            extractor.release()
        }
    }

    @After fun stopAnyRemainingRecording() = runBlocking {
        val graph = context().graph
        if (graph.audioRecording.state.value.phase in setOf("starting", "recording", "unknown"))
            runCatching { graph.audioRecording.stop() }
    }

    @Test fun agentStartAndUserStopReturnsVerifiedPrivateArtifact(): Unit = runBlocking {
        val context = context()
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val graph = context.graph
        allowStartTool(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("nav-BODY").performClick()
            withTimeout(10_000) { while (!graph.uiVisible) delay(50) }

            val start = graph.broker.invoke(invocation("am_audio_recording_start",
                JSONObject().put("maxDurationSeconds", 20)))
            assertEquals(Outcome.COMPLETED, start.outcome)
            val artifactId = start.data.getString("artifactId")
            assertEquals("recording", graph.audioRecording.state.value.phase)
            delay(1_500)

            compose.onNodeWithTag("body-list").performScrollToNode(hasTestTag("audio-record-stop"))
            compose.onNodeWithTag("audio-record-stop").performClick()
            withTimeout(15_000) {
                while (graph.audioRecording.state.value.phase != "complete") delay(50)
            }
            val artifact = graph.audioRecordings.get(artifactId)
            assertNotNull(artifact)
            assertEquals("complete", artifact!!.status)
            assertTrue(artifact.relativePath.startsWith("recordings/"))
            assertTrue(artifact.relativePath.endsWith(".m4a"))
            assertTrue(artifact.bytes > 0L)
            assertEquals(64, artifact.sha256?.length)
            assertTrue(artifact.measuredDurationMs >= 1_000L)
            assertReadableContainer(context, artifact)
            saveArtifactEvidence(context, artifact)
            compose.onNodeWithTag("body-list").performScrollToNode(hasTestTag("audio-artifact-$artifactId"))
            compose.onNodeWithTag("audio-artifact-$artifactId").assertExists()

            val listed = graph.broker.invoke(invocation("am_audio_recordings_list",
                JSONObject().put("limit", 100)))
            assertEquals(Outcome.COMPLETED, listed.outcome)
            val rows = listed.data.getJSONArray("recordings")
            assertTrue((0 until rows.length()).any {
                val row = rows.getJSONObject(it)
                row.optString("artifactId") == artifactId && row.optInt("audioSampleRateHz") > 0
            })
        }
    }

    @Test fun userStartAndAgentStopShareTheSameRecorder(): Unit = runBlocking {
        val context = context()
        val graph = context.graph
        allowStartTool(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("nav-BODY").performClick()
            withTimeout(10_000) { while (!graph.uiVisible) delay(50) }
            compose.onNodeWithTag("body-list").performScrollToNode(hasTestTag("audio-record-start"))
            compose.onNodeWithTag("audio-record-start").performClick()
            withTimeout(10_000) { while (graph.audioRecording.state.value.phase != "recording") delay(50) }
            val artifactId = requireNotNull(graph.audioRecording.state.value.artifactId)
            delay(1_500)

            val stop = graph.broker.invoke(invocation("am_audio_recording_stop"))
            assertEquals(Outcome.COMPLETED, stop.outcome)
            assertEquals(artifactId, stop.data.getString("artifactId"))
            assertTrue(stop.data.getLong("bytes") > 0L)
            withTimeout(10_000) { while (graph.audioRecording.state.value.phase != "complete") delay(50) }
            val artifact = requireNotNull(graph.audioRecordings.get(artifactId))
            assertReadableContainer(context, artifact)
            saveArtifactEvidence(context, artifact)
        }
    }

    @Test fun malformedContainerStaysPartialAndIsNeverExportable(): Unit = runBlocking {
        val context = context()
        val store = context.graph.audioRecordings
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val partial = store.begin(id, now)
        partial.writeBytes(byteArrayOf(0x41, 0x4e, 0x44, 0x52, 0x4f, 0x49, 0x44))

        var containerFailure: BodyFailure? = null
        try {
            store.complete(id, now + 100L, 100L)
            fail("malformed media container must be rejected")
        } catch (failure: BodyFailure) {
            containerFailure = failure
        }
        assertNotNull("invalid media container must not be marked complete", containerFailure)
        val errorCode = requireNotNull(containerFailure).code
        assertTrue(errorCode.startsWith("RECORDING_"))
        assertTrue("malformed output remains at its partial path", partial.isFile)
        assertFalse("malformed output was not renamed to the published M4A",
            File(partial.parentFile, "$id.m4a").exists())

        val failed = requireNotNull(store.markFailed(id, now + 100L, 100L, errorCode))
        assertEquals(AudioRecordingStore.STATUS_FAILED_PARTIAL, failed.status)
        assertEquals(errorCode, failed.errorCode)
        assertTrue(failed.relativePath.endsWith(".partial.m4a"))
        saveArtifactEvidence(context, failed)
        try {
            store.fileForExport(id)
            fail("failed partial audio must not be exportable")
        } catch (failure: BodyFailure) {
            assertEquals("ARTIFACT_NOT_COMPLETE", failure.code)
        }
        assertTrue(store.delete(id, requireNotNull(failed.sha256)).getBoolean("deleted"))
        assertNull(store.get(id))
    }

}
