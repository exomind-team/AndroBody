package org.exomind.androbody.nativeapp.body

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.AtomicFile
import org.exomind.androbody.nativeapp.core.BodyFailure
import org.exomind.androbody.nativeapp.core.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class AudioRecordingArtifact(
    val id: String,
    val status: String,
    val relativePath: String,
    val mimeType: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val measuredDurationMs: Long,
    val bytes: Long,
    val sha256: String?,
    val errorCode: String?,
    val containerDurationMs: Long? = null,
    val audioMimeType: String? = null,
    val audioSampleRateHz: Int? = null,
    val audioChannelCount: Int? = null,
) {
    fun toJson() = jsonObject(
        "artifactId" to id,
        "status" to status,
        "relativePath" to relativePath,
        "mimeType" to mimeType,
        "startedAtEpochMs" to startedAtEpochMs,
        "finishedAtEpochMs" to finishedAtEpochMs,
        "measuredDurationMs" to measuredDurationMs,
        "bytes" to bytes,
        "sha256" to sha256,
        "errorCode" to errorCode,
        "containerDurationMs" to containerDurationMs,
        "audioMimeType" to audioMimeType,
        "audioSampleRateHz" to audioSampleRateHz,
        "audioChannelCount" to audioChannelCount,
    )
}

/** Private app-file storage for audio artifacts. Metadata never includes captured audio or transcript text. */
class AudioRecordingStore(context: Context) {
    private data class AudioContainerMetadata(
        val durationMs: Long,
        val mimeType: String,
        val sampleRateHz: Int,
        val channelCount: Int,
    )

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }
    private val lock = Any()
    private val _items = MutableStateFlow<List<AudioRecordingArtifact>>(emptyList())
    val items: StateFlow<List<AudioRecordingArtifact>> = _items

    init {
        require(root.isDirectory) { "Audio recording directory is unavailable" }
        synchronized(lock) {
            readAll().filter { it.status == STATUS_RECORDING }.forEach { old ->
                val partial = partialFile(old.id)
                write(old.copy(status = STATUS_INTERRUPTED, finishedAtEpochMs = System.currentTimeMillis(),
                    bytes = partial.length(), sha256 = partial.takeIf { it.isFile && it.length() > 0L }?.let(::digest),
                    errorCode = "PROCESS_EXITED_BEFORE_STOP_CONFIRMATION"))
            }
            refreshLocked()
        }
    }

    fun begin(id: String, startedAtEpochMs: Long): File = synchronized(lock) {
        requireValidId(id)
        if (metadataFile(id).exists() || finalFile(id).exists() || partialFile(id).exists())
            throw BodyFailure("DUPLICATE_RECORDING_ID", "录音标识已存在")
        val partial = partialFile(id)
        if (!partial.createNewFile()) throw BodyFailure("RECORDING_FILE_CREATE_FAILED", "无法创建本地录音文件")
        write(AudioRecordingArtifact(id, STATUS_RECORDING, relativePath(id, partial = true), MIME_TYPE,
            startedAtEpochMs, null, 0L, 0L, null, null))
        refreshLocked()
        partial
    }

    fun complete(id: String, finishedAtEpochMs: Long, measuredDurationMs: Long): AudioRecordingArtifact = synchronized(lock) {
        requireValidId(id)
        val partial = partialFile(id)
        val target = finalFile(id)
        if (!partial.isFile || partial.length() <= 0L)
            throw BodyFailure("RECORDING_OUTPUT_EMPTY", "停止已返回，但没有可验证的录音文件")
        val audio = inspectAudioContainer(partial)
        try {
            java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            if (!partial.renameTo(target)) throw BodyFailure("RECORDING_FINALIZE_FAILED", "录音文件发布结果未确认")
        } catch (failure: Exception) {
            throw BodyFailure("RECORDING_FINALIZE_FAILED", "录音文件发布失败：${failure.javaClass.simpleName}")
        }
        val previous = read(metadataFile(id)) ?: throw BodyFailure("RECORDING_METADATA_MISSING", "录音元数据缺失")
        val artifact = AudioRecordingArtifact(id, STATUS_COMPLETE, relativePath(id), MIME_TYPE,
            previous.startedAtEpochMs, finishedAtEpochMs, measuredDurationMs, target.length(), digest(target), null,
            audio.durationMs, audio.mimeType, audio.sampleRateHz, audio.channelCount)
        write(artifact)
        refreshLocked()
        artifact
    }

    fun markFailed(id: String, finishedAtEpochMs: Long, measuredDurationMs: Long, errorCode: String): AudioRecordingArtifact? = synchronized(lock) {
        requireValidId(id)
        val file = when {
            partialFile(id).isFile -> partialFile(id)
            finalFile(id).isFile -> finalFile(id)
            else -> null
        }
        val previous = read(metadataFile(id)) ?: return null
        val artifact = AudioRecordingArtifact(id, STATUS_FAILED_PARTIAL,
            relativePath(id, partial = file == partialFile(id)), MIME_TYPE,
            previous.startedAtEpochMs, finishedAtEpochMs, measuredDurationMs,
            file?.length() ?: 0L, file?.takeIf { it.length() > 0L }?.let(::digest), errorCode)
        write(artifact)
        refreshLocked()
        artifact
    }

    fun get(id: String): AudioRecordingArtifact? = synchronized(lock) {
        requireValidId(id)
        read(metadataFile(id))
    }

    fun fileForExport(id: String): File = synchronized(lock) {
        requireValidId(id)
        val artifact = read(metadataFile(id)) ?: throw BodyFailure("NOT_FOUND", "没有这个录音产物")
        if (artifact.status != STATUS_COMPLETE) throw BodyFailure("ARTIFACT_NOT_COMPLETE", "录音文件尚未完成或无法确认")
        val file = finalFile(id)
        if (!file.isFile || file.length() != artifact.bytes || digest(file) != artifact.sha256)
            throw BodyFailure("ARTIFACT_CHANGED", "录音文件校验失败；没有导出")
        file
    }

    fun copyTo(id: String, uri: Uri): Long {
        val file = fileForExport(id)
        val output = appContext.contentResolver.openOutputStream(uri, "w")
            ?: throw BodyFailure("EXPORT_OPEN_FAILED", "无法打开用户选择的目标")
        FileInputStream(file).use { input -> output.use { source -> input.copyTo(source) } }
        return file.length()
    }

    fun delete(id: String, expectedSha256: String): JSONObject = synchronized(lock) {
        requireValidId(id)
        val artifact = read(metadataFile(id)) ?: throw BodyFailure("NOT_FOUND", "没有这个录音产物")
        if (artifact.status == STATUS_RECORDING) throw BodyFailure("RECORDING_ACTIVE", "不能删除仍在录音的文件")
        val file = if (artifact.relativePath.endsWith(".partial.m4a")) partialFile(id) else finalFile(id)
        if (!file.isFile) throw BodyFailure("NOT_FOUND", "录音文件不存在；元数据已保留")
        val actual = digest(file)
        if (expectedSha256.isBlank() || expectedSha256 != actual)
            throw BodyFailure("CONFLICT", "录音文件已变化；没有删除，请刷新后重新确认")
        if (!file.delete() || file.exists()) throw BodyFailure("DELETE_UNCONFIRMED", "录音文件删除结果未确认")
        val metadata = AtomicFile(metadataFile(id))
        metadata.delete()
        if (metadataFile(id).exists())
            throw BodyFailure("DELETE_METADATA_UNCONFIRMED", "音频已删除，但索引清理未确认")
        refreshLocked()
        jsonObject("artifactId" to id, "deleted" to true, "previousSha256" to actual)
    }

    fun snapshot(limit: Int = MAX_ITEMS): JSONArray = synchronized(lock) {
        JSONArray(_items.value.take(limit.coerceIn(1, MAX_ITEMS)).map { it.toJson() })
    }

    private fun refreshLocked() {
        _items.value = readAll().sortedByDescending { it.startedAtEpochMs }.take(MAX_ITEMS)
    }

    private fun readAll(): List<AudioRecordingArtifact> = root.listFiles { file -> file.extension == "json" }
        .orEmpty().mapNotNull(::read)

    private fun read(file: File): AudioRecordingArtifact? = runCatching {
        val json = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        AudioRecordingArtifact(
            id = json.getString("artifactId"), status = json.getString("status"),
            relativePath = json.getString("relativePath"), mimeType = json.optString("mimeType", MIME_TYPE),
            startedAtEpochMs = json.getLong("startedAtEpochMs"),
            finishedAtEpochMs = if (json.isNull("finishedAtEpochMs")) null else json.optLong("finishedAtEpochMs"),
            measuredDurationMs = json.optLong("measuredDurationMs"), bytes = json.optLong("bytes"),
            sha256 = if (json.isNull("sha256")) null else json.optString("sha256").takeIf(String::isNotBlank),
            errorCode = if (json.isNull("errorCode")) null else json.optString("errorCode").takeIf(String::isNotBlank),
            containerDurationMs = json.optLong("containerDurationMs").takeIf { json.has("containerDurationMs") && !json.isNull("containerDurationMs") },
            audioMimeType = if (json.isNull("audioMimeType")) null else json.optString("audioMimeType").takeIf(String::isNotBlank),
            audioSampleRateHz = json.optInt("audioSampleRateHz").takeIf { json.has("audioSampleRateHz") && !json.isNull("audioSampleRateHz") },
            audioChannelCount = json.optInt("audioChannelCount").takeIf { json.has("audioChannelCount") && !json.isNull("audioChannelCount") },
        )
    }.getOrNull()

    /** Parses the finalized file before it is published as complete; malformed output stays partial. */
    private fun inspectAudioContainer(file: File): AudioContainerMetadata {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw BodyFailure("RECORDING_AUDIO_TRACK_MISSING", "M4A 容器中没有可读取的音频轨道")
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mimeType = format.getString(MediaFormat.KEY_MIME)
                ?: throw BodyFailure("RECORDING_AUDIO_MIME_MISSING", "音频轨道缺少 MIME 类型")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val sampleRateHz = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
            if (durationUs <= 0L || sampleRateHz <= 0 || channelCount <= 0)
                throw BodyFailure("RECORDING_AUDIO_FORMAT_INCOMPLETE", "音频轨道缺少有效时长、采样率或声道数")

            extractor.selectTrack(audioTrackIndex)
            val sampleBytes = extractor.readSampleData(java.nio.ByteBuffer.allocate(SAMPLE_BUFFER_BYTES), 0)
            if (sampleBytes <= 0)
                throw BodyFailure("RECORDING_AUDIO_SAMPLES_MISSING", "音频轨道中没有可读取的样本")

            return AudioContainerMetadata(durationUs / MICROSECONDS_PER_MILLISECOND, mimeType, sampleRateHz, channelCount)
        } catch (failure: BodyFailure) {
            throw failure
        } catch (failure: Exception) {
            throw BodyFailure("RECORDING_CONTAINER_UNREADABLE", "M4A 容器无法解析：${failure.javaClass.simpleName}")
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun write(artifact: AudioRecordingArtifact) {
        val file = AtomicFile(metadataFile(artifact.id))
        var stream: FileOutputStream? = null
        try {
            val output = file.startWrite()
            stream = output
            output.write(artifact.toJson().toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Exception) {
            stream?.let(file::failWrite)
            throw BodyFailure("RECORDING_METADATA_WRITE_FAILED", "录音元数据未能安全保存")
        }
    }

    private fun metadataFile(id: String) = File(root, "$id.json")
    private fun finalFile(id: String) = File(root, "$id.m4a")
    private fun partialFile(id: String) = File(root, "$id.partial.m4a")
    private fun relativePath(id: String, partial: Boolean = false) = "recordings/$id${if (partial) ".partial" else ""}.m4a"

    private fun requireValidId(id: String) {
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not())
            throw BodyFailure("INVALID_ARTIFACT_ID", "录音标识无效")
    }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val MAX_ITEMS = 100
        const val MIME_TYPE = "audio/mp4"
        const val STATUS_RECORDING = "recording"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_INTERRUPTED = "interrupted"
        const val STATUS_FAILED_PARTIAL = "failed_partial"
        private const val DIRECTORY = "recordings"
        private const val MICROSECONDS_PER_MILLISECOND = 1_000L
        private const val SAMPLE_BUFFER_BYTES = 64 * 1_024
    }
}
