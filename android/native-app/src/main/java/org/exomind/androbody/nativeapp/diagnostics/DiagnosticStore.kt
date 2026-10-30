package org.exomind.androbody.nativeapp.diagnostics

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.exomind.androbody.nativeapp.BuildConfig
import org.exomind.androbody.nativeapp.model.endpoint.EndpointCache
import org.exomind.androbody.nativeapp.storage.EncryptedStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/** Safe app-owned metadata journal. Legacy network traces remain readable; logging failure never rewrites the action result. */
class DiagnosticStore(context: Context, private val nowMillis: () -> Long = System::currentTimeMillis) : TraceSink {
    // Keep the pre-console directory as a read-only migration source; new events go to runtime-console.
    private val legacyNetworkDirectory = File(context.filesDir, "native-v1/network-diagnostics")
    private val directory = File(context.filesDir, "native-v1/runtime-console").apply { mkdirs() }
    private val capture = EncryptedStore(context, "network-diagnostic-capture")
    private val armedUntil = AtomicLong(0)
    private val mutable = MutableStateFlow<List<TraceEvent>>(emptyList())
    val events: StateFlow<List<TraceEvent>> = mutable
    private val mutableHealth = MutableStateFlow<String?>(null)
    val health: StateFlow<String?> = mutableHealth
    private val mutableExport = MutableStateFlow<DiagnosticExportObservation?>(null)
    val lastExport: StateFlow<DiagnosticExportObservation?> = mutableExport
    fun recordExport(value: DiagnosticExportObservation) { mutableExport.value = value }
    private val packageName = context.packageName
    private val appContext = context.applicationContext
    val processRunId: String = UUID.randomUUID().toString()
    fun environmentFields(): Map<String, String> = androidTransportContext(appContext)
    private val captureTraces = mutableMapOf<String, Long>()
    init { load() }
    @Synchronized private fun load() {
        val rows = mutableListOf<TraceEvent>()
        readDirectory(legacyNetworkDirectory, LEGACY_KEEP_FILES, rows)
        readDirectory(directory, KEEP_FILES, rows)
        mutable.value = rows.sortedBy { it.atMillis }.takeLast(MAX_MEMORY_EVENTS)
    }
    private fun readDirectory(source: File, keepFiles: Int, rows: MutableList<TraceEvent>) {
        for (index in keepFiles - 1 downTo 0) {
            val file = File(source, "trace-$index.jsonl")
            if (!file.exists()) continue
            try {
                require(file.length() <= MAX_FILE_BYTES + MAX_EVENT_BYTES)
                file.useLines { lines -> lines.forEach { line ->
                    try { rows += decode(JSONObject(line)) }
                    catch (_: Exception) { mutableHealth.value = "诊断尾行损坏或格式不符；已保留原文件，部分事件不可读" }
                } }
            } catch (_: Exception) { mutableHealth.value = "诊断文件读取失败；当前只显示可读/内存事件" }
        }
    }
    @Synchronized override fun record(event: TraceEvent) {
        val stamped = if (event.processRunId == null) event.copy(processRunId = processRunId) else event
        val line = encode(stamped).toString() + "\n"
        mutable.value = (mutable.value + stamped).takeLast(MAX_MEMORY_EVENTS)
        try {
            require(line.toByteArray().size <= MAX_EVENT_BYTES) { "event budget" }
            if (file(0).length() + line.toByteArray().size > MAX_FILE_BYTES) {
                if (file(KEEP_FILES - 1).exists()) check(file(KEEP_FILES - 1).delete())
                for (i in KEEP_FILES - 2 downTo 0) if (file(i).exists()) check(file(i).renameTo(file(i + 1)))
            }
            file(0).appendText(line, Charsets.UTF_8)
        } catch (failure: Exception) {
            mutableHealth.value = "运行事件未能持久保存（${stamped.subsystem}/${stamped.stage} · ${failure.javaClass.simpleName}）；内存事件仍保留，操作结果未被改写"
        }
    }
    fun armContentCapture() { armedUntil.set(nowMillis() + CAPTURE_TTL_MS) }
    fun disarmContentCapture() { armedUntil.set(0) }
    @Synchronized fun beginCapture(traceId: String, origin: String): Boolean {
        // Never capture a background notification or another client's private conversation by accident.
        if (origin !in setOf("native", "diagnostic")) return false
        if (armedUntil.getAndSet(0) < nowMillis()) return false
        val deadline = nowMillis() + CAPTURE_TTL_MS
        captureTraces.clear(); captureTraces[traceId] = deadline
        try { capture.write(JSONObject().put("traceId", traceId).put("expiresAt", deadline).put("parts", JSONArray())) }
        catch (failure: Exception) { mutableHealth.value = "明文诊断捕获无法加密保存（${failure.javaClass.simpleName}）"; captureTraces.clear(); return false }
        return true
    }
    @Synchronized fun recordCapture(traceId: String, attempt: Int, direction: String, bytes: ByteArray, secrets: List<String>) {
        val deadline = captureTraces[traceId] ?: return
        if (nowMillis() > deadline) { captureTraces.remove(traceId); return }
        try {
            val value = capture.read() ?: return
            if (value.optString("traceId") != traceId) return
            val parts = value.getJSONArray("parts")
            if (parts.length() >= 8) return
            parts.put(JSONObject().put("attempt", attempt).put("direction", direction).put("capturedBytes", bytes.size)
                .put("coverage", if (direction.endsWith("_prefix")) "bounded_prefix; full byte count/hash are in transport events" else "bounded_read; see response_body_read.truncated")
                .put("sha256", sha256(bytes)).put("content", JsonTraceView.capture(bytes, secrets))
                .put("limitChars", 65_536).put("redacted", true))
            capture.write(value)
        } catch (failure: Exception) { mutableHealth.value = "诊断内容保存失败（${failure.javaClass.simpleName}）；传输仍按真实结果处理" }
    }
    @Synchronized fun clearContentCapture() { armedUntil.set(0); captureTraces.clear(); capture.write(JSONObject()) }
    @Synchronized fun export(includeContent: Boolean = false): JSONObject = exportEvents(
        allRetainedEvents().filter { it.subsystem == "transport" },
        schema = "andromorph.network-diagnostics.v2",
        scope = "bounded application HTTP trace; not kernel packet capture; metadata is sanitized",
    ).also { result ->
        if (includeContent) {
            val value = capture.read()
            if (value != null && value.optLong("expiresAt") >= nowMillis()) result.put("explicitContentCapture", value)
            else { result.put("captureStatus", "expired_or_absent"); capture.write(JSONObject()); captureTraces.clear() }
        }
    }

    @Synchronized fun exportRuntime(filter: RuntimeLogFilter = RuntimeLogFilter()): JSONObject {
        val retained = allRetainedEvents()
        val rows = retained.filter(filter::matches)
        return exportEvents(rows, "andromorph.runtime-console.v1",
            "AndroBody app-owned runtime metadata; no full system Logcat or content bodies; bounded rotating journal")
            .put("filter", JSONObject().put("minimumLevel", filter.minimumLevel?.name ?: JSONObject.NULL)
                .put("subsystem", filter.subsystem ?: JSONObject.NULL).put("requestId", filter.requestId ?: JSONObject.NULL)
                .put("search", filter.search?.take(120) ?: JSONObject.NULL))
            .put("retainedEventCount", retained.size)
            .put("matchingEventCount", rows.size)
    }

    /** Full bounded journal for the developer console; call from a background dispatcher. */
    @Synchronized fun retainedForConsole(): List<TraceEvent> =
        allRetainedEvents().takeLast(MAX_CONSOLE_EVENTS)

    /** Bounded metadata for Agent self-diagnosis. The caller still receives no prompt or content bodies. */
    @Synchronized fun recentForAgent(requestId: String? = null, subsystem: String? = null, limit: Int = 50): JSONObject {
        val boundedLimit = limit.coerceIn(1, MAX_AGENT_EVENTS)
        val retained = allRetainedEvents()
        val rows = retained.asSequence()
            .filter { requestId.isNullOrBlank() || it.requestId == requestId || it.traceId == requestId }
            .filter { subsystem.isNullOrBlank() || it.subsystem == subsystem }
            .toList().takeLast(boundedLimit)
        return JSONObject().put("scope", "bounded AndroBody runtime metadata; no prompt/key/notification/audio bodies")
            .put("retainedEventsRead", retained.size).put("recentMemoryLimit", MAX_MEMORY_EVENTS)
            .put("journalRotates", true)
            .put("events", JSONArray(rows.map(::agentProjection)))
    }

    @Synchronized private fun exportEvents(rows: List<TraceEvent>, schema: String, scope: String): JSONObject {
        val events = rows.takeLast(MAX_EXPORT_EVENTS)
        val summaries = if (schema == "andromorph.network-diagnostics.v2") {
            events.groupBy { it.traceId }.values.mapNotNull { trace -> runCatching { summarizeTrace(trace) }.getOrNull()?.json() }
        } else emptyList()
        return JSONObject().put("schema", schema)
            .put("exportedAt", System.currentTimeMillis()).put("package", packageName)
            .put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("sourceRevision", BuildConfig.SOURCE_REVISION).put("buildType", BuildConfig.BUILD_TYPE)
            .put("androidApi", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER}/${Build.MODEL}")
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("environmentAtExport", JSONObject(environmentFields()))
            .put("scope", scope).put("eventsLimit", MAX_EXPORT_EVENTS)
            .put("healthWarning", mutableHealth.value ?: JSONObject.NULL)
            .put("summaries", JSONArray(summaries)).put("events", JSONArray(events.map(::encode)))
    }

    private fun allRetainedEvents(): List<TraceEvent> {
        val rows = mutableListOf<TraceEvent>()
        readDirectory(legacyNetworkDirectory, LEGACY_KEEP_FILES, rows)
        readDirectory(directory, KEEP_FILES, rows)
        val seen = rows.mapTo(mutableSetOf(), ::eventKey)
        mutable.value.forEach { event -> if (seen.add(eventKey(event))) rows += event }
        return rows.sortedBy { it.atMillis }
    }

    private fun agentProjection(event: TraceEvent): JSONObject {
        val safeFields = event.fields.entries.asSequence()
            .filterNot { (key, _) -> SENSITIVE_AGENT_FIELD.containsMatchIn(key) }
            .take(MAX_AGENT_FIELDS)
            .associate { (key, value) -> key.take(48) to DiagnosticRedaction.text(value, limit = MAX_AGENT_FIELD_CHARS) }
        return encode(event.copy(fields = safeFields))
    }

    private fun eventKey(event: TraceEvent): String =
        "${event.processRunId.orEmpty()}|${event.traceId}|${event.requestId}|${event.sequence}|${event.atMillis}|${event.stage}"

    private fun file(index: Int) = File(directory, "trace-$index.jsonl")
    companion object {
        const val MAX_MEMORY_EVENTS = 1200
        private const val MAX_AGENT_EVENTS = 50
        private const val MAX_AGENT_FIELDS = 16
        private const val MAX_AGENT_FIELD_CHARS = 256
        const val MAX_CONSOLE_EVENTS = 24_000
        private const val MAX_EXPORT_EVENTS = MAX_CONSOLE_EVENTS
        private const val LEGACY_KEEP_FILES = 4
        private const val KEEP_FILES = 6
        private const val MAX_FILE_BYTES = 1024 * 1024
        private const val MAX_EVENT_BYTES = 64 * 1024
        private const val CAPTURE_TTL_MS = 10 * 60_000L
        private val SENSITIVE_AGENT_FIELD = Regex("(?i)(prompt|notification.?body|message.?body|transcript|audio.?data|raw.?content|api.?key|authorization|password|secret|bearer|cookie)")
        fun encode(event: TraceEvent): JSONObject = JSONObject().put("traceId", event.traceId).put("requestId", event.requestId)
            .put("origin", event.origin).put("sequence", event.sequence).put("atMillis", event.atMillis)
            .put("elapsedMicros", event.elapsedMicros).put("attempt", event.attempt).put("stage", event.stage)
            .put("fields", JSONObject(event.fields)).put("level", event.level).put("subsystem", event.subsystem)
            .put("evidenceKind", event.evidenceKind ?: JSONObject.NULL).put("feasibility", event.feasibility ?: JSONObject.NULL)
            .put("processRunId", event.processRunId ?: JSONObject.NULL)
        private fun decode(value: JSONObject): TraceEvent {
            val fields = value.getJSONObject("fields")
            val decodedFields = fields.keys().asSequence().associateWith { fields.getString(it) }
            val stage = value.getString("stage")
            val storedLevel = value.optString("level")
            val storedEvidence = value.optString("evidenceKind")
            return TraceEvent(value.getString("traceId"), value.getString("requestId"), value.getString("origin"),
                value.getLong("sequence"), value.getLong("atMillis"), value.getLong("elapsedMicros"),
                value.getInt("attempt"), stage, decodedFields,
                storedLevel.takeIf { name -> RuntimeLogLevel.entries.any { it.name == name } } ?: inferredTraceLevel(stage, decodedFields).name,
                value.optString("subsystem").takeIf(String::isNotBlank) ?: "transport",
                storedEvidence.takeIf { name -> EvidenceKind.entries.any { it.name == name } } ?: inferredEvidenceKind(stage).name,
                value.optString("feasibility").takeIf { name -> CapabilityFeasibility.entries.any { it.name == name } },
                value.optString("processRunId").takeIf { it.isNotBlank() && it != "null" })
        }
    }
}

/** Disposable, encrypted success hints. Config/key/protocol/operation changes use another key. */
class PersistentEndpointCache(context: Context) : EndpointCache {
    private val store = EncryptedStore(context, "endpoint-success-cache")
    private var values: JSONObject? = null
    @Volatile var warning: String? = null; private set
    private fun values(): JSONObject {
        values?.let { return it }
        return try { (store.read() ?: JSONObject()).also { values = it } }
        catch (_: Exception) { warning = "成功端点缓存不可读；继续用确定性路径计划"; JSONObject().also { values = it } }
    }
    @Synchronized override fun read(key: String): String? = values().optJSONObject(key)?.takeIf {
        System.currentTimeMillis() - it.optLong("at") in 0..TTL_MS
    }?.optString("endpoint")?.takeIf(String::isNotBlank)
    @Synchronized override fun remember(key: String, endpoint: String) {
        val data = values()
        data.put(key, JSONObject().put("endpoint", endpoint).put("at", System.currentTimeMillis()))
        while (data.length() > 64) {
            val oldest = data.keys().asSequence().minByOrNull { data.optJSONObject(it)?.optLong("at") ?: 0 } ?: break
            data.remove(oldest)
        }
        save(data)
    }
    @Synchronized override fun forget(key: String) { values().remove(key); save(values()) }
    private fun save(data: JSONObject) {
        try { store.write(data) } catch (_: Exception) { warning = "成功端点缓存未持久化；当前进程仍可使用" }
    }
    companion object { private const val TTL_MS = 24 * 60 * 60_000L }
}
