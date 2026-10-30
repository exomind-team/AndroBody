package org.exomind.androbody.nativeapp.body

import org.exomind.androbody.nativeapp.core.BodyFailure
import org.exomind.androbody.nativeapp.core.BodyTool
import org.exomind.androbody.nativeapp.core.Capability
import org.exomind.androbody.nativeapp.core.Effect
import org.exomind.androbody.nativeapp.core.jsonObject
import org.exomind.androbody.nativeapp.core.objectSchema
import org.exomind.androbody.nativeapp.core.stringRequired
import org.exomind.androbody.nativeapp.core.textField

/** Audio remains local: tools can start, stop, enumerate, and delete verified files, never read audio bytes. */
fun audioRecordingTools(
    controller: AudioRecordingController,
    store: AudioRecordingStore,
    isAppVisible: () -> Boolean,
): List<BodyTool> = listOf(
    SimpleTool(Capability(
        name = "am_audio_recording_start",
        title = "开始录音",
        description = "Start a user-visible, bounded AAC/M4A microphone recording. Requires the Android RECORD_AUDIO grant and a visible AndroBody activity. Saves only to app-private storage; returns an artifact ID and relative path, never audio bytes or transcript text.",
        parameters = objectSchema(jsonObject("maxDurationSeconds" to jsonObject("type" to "integer", "minimum" to AudioRecordingController.MIN_DURATION_SECONDS,
            "maximum" to AudioRecordingController.MAX_DURATION_SECONDS))),
        effect = Effect.USER_VISIBLE, gatewayEligible = false, needsConfirmation = true,
    )) { invocation ->
        if (!isAppVisible()) throw BodyFailure("FOREGROUND_REQUIRED", "请回到 AndroBody 前台后再开始麦克风录音")
        controller.start(invocation.arguments.optInt("maxDurationSeconds", AudioRecordingController.DEFAULT_MAX_DURATION_SECONDS))
    },
    SimpleTool(Capability(
        name = "am_audio_recording_stop",
        title = "停止录音",
        description = "Stop the current local microphone recording and verify its finalized file size and SHA-256. Stop is allowed without a second confirmation so recording can always end promptly.",
        parameters = objectSchema(), effect = Effect.CONTROL, gatewayEligible = false, needsConfirmation = false,
    )) {
        controller.stop()
    },
    SimpleTool(Capability(
        name = "am_audio_recordings_list",
        title = "查看录音产物",
        description = "List bounded app-private audio recording metadata and relative paths. Does not read, transcribe, or send audio bytes to a model.",
        parameters = objectSchema(jsonObject("limit" to jsonObject("type" to "integer", "minimum" to 1, "maximum" to AudioRecordingStore.MAX_ITEMS))),
        effect = Effect.READ_WORKSPACE, gatewayEligible = false,
    )) { invocation ->
        val rows = store.snapshot(invocation.arguments.optInt("limit", 20))
        org.exomind.androbody.nativeapp.core.ToolResult.completed(
            jsonObject("count" to rows.length(), "recordings" to rows),
            "App-private recording metadata only; no audio content was read or uploaded")
    },
    SimpleTool(Capability(
        name = "am_audio_recording_delete",
        title = "删除录音产物",
        description = "Delete one inactive app-private audio artifact only if its current SHA-256 matches expectedSha256. Active recordings and changed files are not deleted.",
        parameters = objectSchema(jsonObject("artifactId" to textField("Recording artifact UUID", 36),
            "expectedSha256" to textField("Current file SHA-256", 64)), listOf("artifactId", "expectedSha256")),
        effect = Effect.CONTROL, gatewayEligible = false, needsConfirmation = true,
    )) { invocation ->
        org.exomind.androbody.nativeapp.core.ToolResult.completed(
            store.delete(invocation.arguments.stringRequired("artifactId", 36),
                invocation.arguments.stringRequired("expectedSha256", 64)),
            "One completed app-private audio file was deleted after SHA-256 comparison")
    },
)
