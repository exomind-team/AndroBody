package org.exomind.androbody.nativeapp.body

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.exomind.androbody.nativeapp.core.BodyFailure
import org.exomind.androbody.nativeapp.MainActivity
import org.exomind.androbody.nativeapp.R
import org.exomind.androbody.nativeapp.graph
import java.lang.ref.WeakReference

/** User-visible microphone owner. Starts only from an already-visible, permission-granted app request. */
class AudioRecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorderLock = Mutex()
    private var recorder: MediaRecorder? = null
    @Volatile private var recordingId: String? = null
    @Volatile private var stopInProgress: Boolean = false
    private var startedAtEpochMs: Long = 0L
    private var maximumDurationSeconds: Int = AudioRecordingController.DEFAULT_MAX_DURATION_SECONDS
    private var autoStop: Job? = null

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
        val channel = NotificationChannel(CHANNEL_ID, "本机录音", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFromVisibleRequest(intent, startId)
            ACTION_STOP_NOTIFICATION -> requestStop(operationId = null, stopCause = "notification_action")
            else -> {
                graph.runtimeLog.record("audio", "recording_service_unrecognized_action", origin = "system",
                    level = org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel.WARN,
                    evidenceKind = org.exomind.androbody.nativeapp.diagnostics.EvidenceKind.PREFLIGHT,
                    feasibility = org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility.NOT_ASSESSED,
                    fields = mapOf("hasAction" to (!intent?.action.isNullOrBlank()).toString()))
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startFromVisibleRequest(intent: Intent, startId: Int) {
        val id = intent.getStringExtra(EXTRA_RECORDING_ID)
        val startedAt = intent.getLongExtra(EXTRA_STARTED_AT, 0L)
        val maxDuration = intent.getIntExtra(EXTRA_MAX_DURATION_SECONDS,
            AudioRecordingController.DEFAULT_MAX_DURATION_SECONDS)
        if (id.isNullOrBlank() || startedAt <= 0L || maxDuration !in AudioRecordingController.MIN_DURATION_SECONDS..AudioRecordingController.MAX_DURATION_SECONDS) {
            graph.audioRecording.onStartFailed(id.orEmpty(), startedAt, maxDuration, "INVALID_START_COMMAND", null)
            stopSelf(startId)
            return
        }
        if (recordingId != null || recorder != null) {
            graph.audioRecording.onStartFailed(id, startedAt, maxDuration, "RECORDING_ALREADY_ACTIVE", null)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            graph.audioRecording.onStartFailed(id, startedAt, maxDuration, "RECORD_AUDIO_NOT_GRANTED", null)
            stopSelf(startId)
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, buildNotification())
        } catch (failure: Exception) {
            graph.audioRecording.onStartFailed(id, startedAt, maxDuration,
                "FOREGROUND_START_${failure.javaClass.simpleName}", failure.javaClass.name)
            stopSelf(startId)
            return
        }

        recordingId = id
        stopInProgress = false
        startedAtEpochMs = startedAt
        maximumDurationSeconds = maxDuration
        serviceScope.launch { beginCapture(id, startedAt, maxDuration, startId) }
    }

    private suspend fun beginCapture(id: String, startedAt: Long, maxDuration: Int, startId: Int) = recorderLock.withLock {
        var created: MediaRecorder? = null
        try {
            val output = graph.audioRecordings.begin(id, startedAt)
            created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this@AudioRecordingService)
                else @Suppress("DEPRECATION") MediaRecorder()
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioEncodingBitRate(AUDIO_BITRATE_BPS)
            created.setAudioSamplingRate(AUDIO_SAMPLE_RATE_HZ)
            created.setOutputFile(output.absolutePath)
            created.setOnErrorListener { _, what, extra ->
                graph.runtimeLog.record("audio", "media_recorder_error", requestId = id, origin = "system",
                    level = org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel.ERROR,
                    evidenceKind = org.exomind.androbody.nativeapp.diagnostics.EvidenceKind.RESULT,
                    feasibility = org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility.FAILED,
                    fields = mapOf("what" to what.toString(), "extra" to extra.toString()))
                serviceScope.launch { requestStop(operationId = null, stopCause = "media_recorder_error") }
            }
            created.prepare()
            created.start()
            recorder = created
            graph.audioRecording.onRecordingStarted(id, startedAt, maxDuration)
            autoStop?.cancel()
            autoStop = serviceScope.launch {
                delay(maxDuration * 1_000L)
                requestStop(operationId = null, stopCause = "maximum_duration_reached")
            }
        } catch (failure: Exception) {
            runCatching { created?.reset() }
            runCatching { created?.release() }
            val partial = runCatching { graph.audioRecordings.markFailed(id, System.currentTimeMillis(),
                (System.currentTimeMillis() - startedAt).coerceAtLeast(0L), "MEDIA_RECORDER_${failure.javaClass.simpleName}") }.getOrNull()
            graph.audioRecording.onStartFailed(id, startedAt, maxDuration,
                "MEDIA_RECORDER_${failure.javaClass.simpleName}", failure.javaClass.name)
            recordingId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            if (partial != null) graph.runtimeLog.record("audio", "recording_start_left_partial_file", requestId = id,
                origin = "system", level = org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel.WARN,
                evidenceKind = org.exomind.androbody.nativeapp.diagnostics.EvidenceKind.RECOVERY,
                feasibility = org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility.FAILED,
                fields = mapOf("relativePath" to partial.relativePath, "bytes" to partial.bytes.toString()))
        }
    }

    private fun requestStop(operationId: String?, stopCause: String): Boolean {
        val id = recordingId ?: return false
        val shouldLaunch = synchronized(this) {
            if (recordingId != id) false
            else if (stopInProgress) false
            else { stopInProgress = true; true }
        }
        graph.audioRecording.onRecordingStopRequested(id)
        if (shouldLaunch) serviceScope.launch { finishCapture(id, operationId, stopCause) }
        return true
    }

    private suspend fun finishCapture(id: String, operationId: String?, stopCause: String) = recorderLock.withLock {
        val active = recorder
        if (active == null) {
            val artifact = runCatching { graph.audioRecordings.markFailed(id, System.currentTimeMillis(),
                (System.currentTimeMillis() - startedAtEpochMs).coerceAtLeast(0L), "RECORDER_OWNER_MISSING") }.getOrNull()
            graph.audioRecording.onRecordingStopFailed(operationId, id, "RECORDER_OWNER_MISSING", artifact, null)
            stopAndRelease()
            return@withLock
        }
        val finishedAt = System.currentTimeMillis()
        val duration = (finishedAt - startedAtEpochMs).coerceAtLeast(0L)
        try {
            active.stop()
            active.reset()
            active.release()
            recorder = null
            val artifact = graph.audioRecordings.complete(id, finishedAt, duration)
            graph.audioRecording.onRecordingStopped(operationId, artifact, stopCause)
        } catch (failure: Exception) {
            runCatching { active.reset() }
            runCatching { active.release() }
            recorder = null
            val errorCode = (failure as? BodyFailure)?.code
                ?: "MEDIA_RECORDER_STOP_${failure.javaClass.simpleName}"
            val artifact = runCatching { graph.audioRecordings.markFailed(id, finishedAt, duration,
                errorCode) }.getOrNull()
            graph.audioRecording.onRecordingStopFailed(operationId, id, errorCode, artifact, failure.javaClass.name)
        } finally {
            recorder = null
            recordingId = null
            stopInProgress = false
            autoStop?.cancel()
            autoStop = null
            stopAndRelease()
        }
    }

    private fun stopAndRelease() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_morph)
        .setContentTitle("AndroBody 正在录音")
        .setContentText("音频保存在本机；Agent 不会自动上传录音内容")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, "停止录音", PendingIntent.getService(this, NOTIFICATION_ID,
            Intent(this, AudioRecordingService::class.java).setAction(ACTION_STOP_NOTIFICATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    override fun onDestroy() {
        val id = recordingId
        val active = recorder
        if (id != null && active != null) {
            serviceScope.launch {
                runCatching { active.reset() }
                runCatching { active.release() }
                val artifact = runCatching { graph.audioRecordings.markFailed(id, System.currentTimeMillis(),
                    (System.currentTimeMillis() - startedAtEpochMs).coerceAtLeast(0L), "SERVICE_DESTROYED_BEFORE_STOP") }.getOrNull()
                graph.audioRecording.onRecordingInterrupted(id, artifact, "SERVICE_DESTROYED_BEFORE_STOP")
                serviceScope.cancel()
            }
        } else serviceScope.cancel()
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "org.exomind.andromorph.audio.START"
        const val ACTION_STOP_NOTIFICATION = "org.exomind.andromorph.audio.STOP_NOTIFICATION"
        const val EXTRA_RECORDING_ID = "recordingId"
        const val EXTRA_STARTED_AT = "startedAtEpochMs"
        const val EXTRA_MAX_DURATION_SECONDS = "maxDurationSeconds"
        private const val CHANNEL_ID = "audio-recording"
        private const val NOTIFICATION_ID = 104
        private const val AUDIO_BITRATE_BPS = 64_000
        private const val AUDIO_SAMPLE_RATE_HZ = 44_100
        @Volatile private var activeService: WeakReference<AudioRecordingService>? = null

        fun startIntent(context: Context, id: String, startedAt: Long, maxDurationSeconds: Int) =
            Intent(context, AudioRecordingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RECORDING_ID, id).putExtra(EXTRA_STARTED_AT, startedAt)
                .putExtra(EXTRA_MAX_DURATION_SECONDS, maxDurationSeconds)

        fun requestStop(operationId: String?): Boolean = activeService?.get()?.requestStop(operationId, "agent_or_user") == true
    }
}
