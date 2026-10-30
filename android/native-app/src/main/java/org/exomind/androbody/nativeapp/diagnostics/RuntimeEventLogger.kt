package org.exomind.androbody.nativeapp.diagnostics

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** App-owned trace writer. It records lifecycle/effect metadata, never free-form bodies or secrets. */
class RuntimeEventLogger(
    private val sink: TraceSink,
    val processRunId: String = UUID.randomUUID().toString(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val startedAtNanos = nowNanos()
    private val sequence = AtomicLong()

    fun record(
        subsystem: String,
        stage: String,
        requestId: String? = null,
        origin: String = "app",
        level: RuntimeLogLevel = RuntimeLogLevel.INFO,
        evidenceKind: EvidenceKind? = null,
        feasibility: CapabilityFeasibility? = null,
        fields: Map<String, String> = emptyMap(),
    ) {
        val safeRequestId = requestId?.takeIf(String::isNotBlank) ?: processRunId
        val safeFields = sanitizeFields(fields) + ("processRunId" to processRunId)
        runCatching {
            sink.record(TraceEvent(
                traceId = safeRequestId,
                requestId = safeRequestId,
                origin = origin.take(40),
                sequence = sequence.incrementAndGet(),
                atMillis = nowMillis(),
                elapsedMicros = ((nowNanos() - startedAtNanos).coerceAtLeast(0L)) / 1000L,
                attempt = 0,
                stage = stage.take(80),
                fields = safeFields,
                level = level.name,
                subsystem = subsystem.take(48),
                evidenceKind = evidenceKind?.name,
                feasibility = feasibility?.name,
                processRunId = processRunId,
            ))
        }
    }

    /** Stack metadata helps locate app crashes without persisting exception messages or user data. */
    fun recordUncaught(threadName: String, failure: Throwable) {
        record(
            subsystem = "lifecycle",
            stage = "uncaught_exception",
            requestId = processRunId,
            origin = "app",
            level = RuntimeLogLevel.ERROR,
            evidenceKind = EvidenceKind.RESULT,
            fields = mapOf(
                "turnOutcome" to "UNCAUGHT_EXCEPTION",
                "thread" to threadName,
                "exceptionClass" to failure.javaClass.name,
                "stackFrames" to failure.stackTrace.take(MAX_STACK_FRAMES).joinToString(" <- ") {
                    "${it.className}.${it.methodName}:${it.lineNumber}"
                },
                "causeClass" to failure.cause?.javaClass?.name.orEmpty(),
            ),
        )
    }

    private fun sanitizeFields(values: Map<String, String>): Map<String, String> =
        values.entries.asSequence()
            .filterNot { (key, _) -> SENSITIVE_KEY.containsMatchIn(key) }
            .take(MAX_FIELDS)
            .associate { (key, value) ->
                key.take(64) to DiagnosticRedaction.text(value, limit = MAX_VALUE_CHARS)
            }

    companion object {
        private const val MAX_FIELDS = 32
        private const val MAX_VALUE_CHARS = 512
        private const val MAX_STACK_FRAMES = 24
        private val SENSITIVE_KEY = Regex("(?i)(prompt|notification.?body|message.?body|transcript|audio.?data|raw.?content|api.?key|authorization|password|secret|bearer|cookie)")
    }
}
