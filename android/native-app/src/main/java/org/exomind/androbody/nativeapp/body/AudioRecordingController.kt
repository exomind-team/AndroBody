package org.exomind.androbody.nativeapp.body

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.exomind.androbody.nativeapp.core.BodyFailure
import org.exomind.androbody.nativeapp.core.Outcome
import org.exomind.androbody.nativeapp.core.ToolResult
import org.exomind.androbody.nativeapp.core.jsonObject
import org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility
import org.exomind.androbody.nativeapp.diagnostics.EvidenceKind
import org.exomind.androbody.nativeapp.diagnostics.RuntimeEventLogger
import org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AudioRecordingRuntimeState(
    val phase: String = "idle",
    val artifactId: String? = null,
    val startedAtEpochMs: Long? = null,
    val maxDurationSeconds: Int? = null,
    val artifact: AudioRecordingArtifact? = null,
    val errorCode: String? = null,
)

/** Coordinates tool/UI commands with the microphone foreground service and its verified local files. */
class AudioRecordingController(
    context: Context,
    val store: AudioRecordingStore,
    private val runtimeLog: RuntimeEventLogger,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(
        AudioRecordingRuntimeState(artifact = store.items.value.firstOrNull { it.status == AudioRecordingStore.STATUS_COMPLETE })
    )
    val state: StateFlow<AudioRecordingRuntimeState> = _state
    private val startResults = ConcurrentHashMap<String, CompletableDeferred<ToolResult>>()
    private val stopResults = ConcurrentHashMap<String, CompletableDeferred<ToolResult>>()

    suspend fun start(maxDurationSeconds: Int): ToolResult {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw BodyFailure("NEEDS_GRANT", "请在身体页授权麦克风后再开始录音")
        if (maxDurationSeconds !in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS)
            throw BodyFailure("INVALID_DURATION", "录音时长须在 $MIN_DURATION_SECONDS 到 $MAX_DURATION_SECONDS 秒之间")
        if (_state.value.phase in ACTIVE_PHASES || _state.value.phase == "unknown")
            throw BodyFailure("RECORDING_ALREADY_ACTIVE", "已有录音操作进行中；请先停止或核对状态")

        val id = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<ToolResult>()
        startResults[id] = waiter
        val now = System.currentTimeMillis()
        _state.value = AudioRecordingRuntimeState("starting", id, now, maxDurationSeconds)
        runtimeLog.record("audio", "recording_start_requested", requestId = id, origin = "native",
            level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.ACTUAL_ATTEMPT,
            feasibility = CapabilityFeasibility.SUBMITTED,
            fields = mapOf("maxDurationSeconds" to maxDurationSeconds.toString(), "mimeType" to AudioRecordingStore.MIME_TYPE))
        try {
            withContext(Dispatchers.Main.immediate) {
                ContextCompat.startForegroundService(appContext, AudioRecordingService.startIntent(appContext, id, now, maxDurationSeconds))
            }
        } catch (failure: SecurityException) {
            startResults.remove(id)
            val code = "ANDROID_PERMISSION_DENIED"
            _state.value = AudioRecordingRuntimeState("failed", id, now, maxDurationSeconds, errorCode = code)
            runtimeLog.record("audio", "recording_start_dispatch_denied", requestId = id, origin = "native",
                level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
                feasibility = CapabilityFeasibility.NEEDS_PERMISSION,
                fields = mapOf("exceptionClass" to failure.javaClass.name))
            return ToolResult(Outcome.DENIED, jsonObject("status" to "failed", "errorCode" to code),
                "Android denied microphone foreground service start; no recording result was confirmed")
        } catch (failure: Exception) {
            startResults.remove(id)
            val code = failure.javaClass.simpleName.ifBlank { "FOREGROUND_SERVICE_START_FAILED" }
            _state.value = AudioRecordingRuntimeState("failed", id, now, maxDurationSeconds, errorCode = code)
            runtimeLog.record("audio", "recording_start_dispatch_failed", requestId = id, origin = "native",
                level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
                feasibility = CapabilityFeasibility.FAILED,
                fields = mapOf("exceptionClass" to failure.javaClass.name))
            return ToolResult(Outcome.FAILED, jsonObject("status" to "failed", "errorCode" to code),
                "The Android foreground service did not accept the recording start request")
        }
        return try {
            withTimeout(START_RESULT_TIMEOUT_MS) { waiter.await() }
        } catch (_: TimeoutCancellationException) {
            startResults.remove(id)
            _state.value = _state.value.copy(phase = "unknown", errorCode = "START_RESULT_TIMEOUT")
            runtimeLog.record("audio", "recording_start_result_unknown", requestId = id, origin = "native",
                level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.RESULT,
                feasibility = CapabilityFeasibility.UNKNOWN,
                fields = mapOf("timeoutMs" to START_RESULT_TIMEOUT_MS.toString()))
            ToolResult(Outcome.UNKNOWN, jsonObject("status" to "unknown", "artifactId" to id,
                "errorCode" to "START_RESULT_TIMEOUT"),
                "The foreground service received a start request but did not confirm MediaRecorder.start; inspect runtime trace before retrying")
        } finally {
            startResults.remove(id)
        }
    }

    suspend fun stop(): ToolResult {
        val before = _state.value
        if (before.phase == "complete" && before.artifact != null)
            return ToolResult.completed(before.artifact.toJson().put("alreadyStopped", true),
                "The recorder already confirmed this completed artifact; no second stop was sent")
        if (before.phase == "stopping")
            return ToolResult(Outcome.SUBMITTED, jsonObject("status" to "stopping", "artifactId" to before.artifactId),
                "A stop request is already being finalized; do not issue another recorder operation")
        if (before.phase !in STOPPABLE_PHASES || before.artifactId == null)
            throw BodyFailure("NO_ACTIVE_RECORDING", "当前没有可停止的录音；请先核对录音状态与追踪")

        val operationId = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<ToolResult>()
        stopResults[operationId] = waiter
        _state.value = before.copy(phase = "stopping")
        runtimeLog.record("audio", "recording_stop_requested", requestId = before.artifactId, origin = "native",
            level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.ACTUAL_ATTEMPT,
            feasibility = CapabilityFeasibility.SUBMITTED,
            fields = mapOf("stopOperationId" to operationId))
        if (!AudioRecordingService.requestStop(operationId)) {
            stopResults.remove(operationId)
            _state.value = before.copy(phase = "unknown", errorCode = "RECORDING_SERVICE_NOT_RUNNING")
            runtimeLog.record("audio", "recording_stop_owner_missing", requestId = before.artifactId, origin = "native",
                level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.RESULT,
                feasibility = CapabilityFeasibility.UNKNOWN, fields = mapOf("stopOperationId" to operationId))
            return ToolResult(Outcome.UNKNOWN, jsonObject("status" to "unknown", "artifactId" to before.artifactId,
                "errorCode" to "RECORDING_SERVICE_NOT_RUNNING"),
                "No active foreground-service owner confirmed the stop; inspect recording files and runtime trace before retrying")
        }
        return try {
            withTimeout(STOP_RESULT_TIMEOUT_MS) { waiter.await() }
        } catch (_: TimeoutCancellationException) {
            stopResults.remove(operationId)
            _state.value = _state.value.copy(phase = "unknown", errorCode = "STOP_RESULT_TIMEOUT")
            runtimeLog.record("audio", "recording_stop_result_unknown", requestId = before.artifactId, origin = "native",
                level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.RESULT,
                feasibility = CapabilityFeasibility.UNKNOWN,
                fields = mapOf("stopOperationId" to operationId, "timeoutMs" to STOP_RESULT_TIMEOUT_MS.toString()))
            ToolResult(Outcome.UNKNOWN, jsonObject("status" to "unknown", "artifactId" to before.artifactId,
                "errorCode" to "STOP_RESULT_TIMEOUT"),
                "The stop request was submitted but no finalized file was confirmed; inspect recordings and trace before retrying")
        } finally {
            stopResults.remove(operationId)
        }
    }

    internal fun onRecordingStarted(id: String, startedAtEpochMs: Long, maxDurationSeconds: Int) {
        _state.value = AudioRecordingRuntimeState("recording", id, startedAtEpochMs, maxDurationSeconds)
        runtimeLog.record("audio", "recording_started", requestId = id, origin = "system",
            level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.PLATFORM_RESPONSE,
            feasibility = CapabilityFeasibility.AVAILABLE,
            fields = mapOf("startedAtEpochMs" to startedAtEpochMs.toString(), "maxDurationSeconds" to maxDurationSeconds.toString()))
        startResults.remove(id)?.complete(ToolResult.completed(
            jsonObject("status" to "recording", "artifactId" to id, "startedAtEpochMs" to startedAtEpochMs,
                "maxDurationSeconds" to maxDurationSeconds, "mimeType" to AudioRecordingStore.MIME_TYPE),
            "MediaRecorder.start returned; an active microphone recording is owned by the foreground service"))
    }

    internal fun onStartFailed(id: String, startedAtEpochMs: Long, maxDurationSeconds: Int, errorCode: String, exceptionClass: String?) {
        _state.value = AudioRecordingRuntimeState("failed", id, startedAtEpochMs, maxDurationSeconds, errorCode = errorCode)
        runtimeLog.record("audio", "recording_start_failed", requestId = id, origin = "system",
            level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.RESULT,
            feasibility = CapabilityFeasibility.FAILED,
            fields = mapOf("errorCode" to errorCode, "exceptionClass" to exceptionClass.orEmpty()))
        startResults.remove(id)?.complete(ToolResult(Outcome.FAILED,
            jsonObject("status" to "failed", "artifactId" to id, "errorCode" to errorCode),
            "Android did not confirm MediaRecorder.start; no completed audio artifact is claimed"))
    }

    internal fun onRecordingStopped(operationId: String?, artifact: AudioRecordingArtifact, stopCause: String) {
        _state.value = AudioRecordingRuntimeState("complete", artifact.id, artifact.startedAtEpochMs,
            artifact = artifact)
        runtimeLog.record("audio", "recording_file_finalized", requestId = artifact.id, origin = "system",
            level = RuntimeLogLevel.INFO, evidenceKind = EvidenceKind.RESULT,
            feasibility = CapabilityFeasibility.COMPLETED,
            fields = mapOf("relativePath" to artifact.relativePath, "bytes" to artifact.bytes.toString(),
                "sha256" to artifact.sha256.orEmpty(), "measuredDurationMs" to artifact.measuredDurationMs.toString(),
                "stopCause" to stopCause))
        operationId?.let { stopResults.remove(it)?.complete(ToolResult.completed(
            artifact.toJson().put("stopCause", stopCause),
            "MediaRecorder.stop returned; the local M4A file exists and its byte count and SHA-256 were read back")) }
    }

    internal fun onRecordingStopRequested(id: String) {
        _state.value = _state.value.copy(phase = "stopping", artifactId = id, errorCode = null)
    }

    internal fun onRecordingStopFailed(operationId: String?, id: String, errorCode: String,
                                       artifact: AudioRecordingArtifact?, exceptionClass: String?) {
        _state.value = AudioRecordingRuntimeState("failed", id, artifact?.startedAtEpochMs,
            artifact = artifact, errorCode = errorCode)
        runtimeLog.record("audio", "recording_stop_failed", requestId = id, origin = "system",
            level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.RESULT,
            feasibility = CapabilityFeasibility.FAILED,
            fields = mapOf("errorCode" to errorCode, "exceptionClass" to exceptionClass.orEmpty(),
                "partialPath" to artifact?.relativePath.orEmpty()))
        operationId?.let { stopResults.remove(it)?.complete(ToolResult(
            Outcome.FAILED, jsonObject("status" to "failed", "artifactId" to id,
                "errorCode" to errorCode, "partialArtifact" to artifact?.toJson()),
            "MediaRecorder.stop did not confirm a completed audio file; any partial file is marked failed and is not a completed recording")) }
    }

    internal fun onRecordingInterrupted(id: String, artifact: AudioRecordingArtifact?, errorCode: String) {
        _state.value = AudioRecordingRuntimeState("unknown", id, artifact?.startedAtEpochMs,
            artifact = artifact, errorCode = errorCode)
        runtimeLog.record("audio", "recording_interrupted", requestId = id, origin = "system",
            level = RuntimeLogLevel.ERROR, evidenceKind = EvidenceKind.RECOVERY,
            feasibility = CapabilityFeasibility.UNKNOWN,
            fields = mapOf("errorCode" to errorCode, "partialPath" to artifact?.relativePath.orEmpty()))
        startResults.remove(id)?.complete(ToolResult(Outcome.UNKNOWN,
            jsonObject("status" to "unknown", "artifactId" to id, "errorCode" to errorCode),
            "The service stopped before it could confirm a finalized file"))
    }

    companion object {
        const val DEFAULT_MAX_DURATION_SECONDS = 1_800
        const val MIN_DURATION_SECONDS = 2
        const val MAX_DURATION_SECONDS = 3_600
        private const val START_RESULT_TIMEOUT_MS = 10_000L
        private const val STOP_RESULT_TIMEOUT_MS = 15_000L
        private val ACTIVE_PHASES = setOf("starting", "recording", "stopping")
        private val STOPPABLE_PHASES = setOf("starting", "recording", "unknown")
    }
}
