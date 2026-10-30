package org.exomind.androbody.nativeapp.diagnostics

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class RuntimeLogLevel(val priority: Int) {
    VERBOSE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4);

    companion object {
        fun parse(value: String): RuntimeLogLevel = entries.firstOrNull { it.name == value } ?: INFO
    }
}

/** Evidence stage is separate from feasibility: static evidence cannot establish a runtime denial. */
enum class EvidenceKind { THEORETICAL, STATIC_CANDIDATE, IMPLEMENTATION, PREFLIGHT, AGENT_REQUEST, ACTUAL_ATTEMPT, PLATFORM_RESPONSE, RESULT, RECOVERY }
enum class CapabilityFeasibility {
    NOT_ASSESSED, NOT_TESTED, IMPLEMENTED, AVAILABLE, UNAVAILABLE, NEEDS_PERMISSION, NEEDS_USER_ACTION,
    POLICY_DENIED, USER_DENIED, BLOCKER_CANDIDATE, BLOCKED_OBSERVED, SUBMITTED, COMPLETED,
    FAILED, UNKNOWN, NOT_IMPLEMENTED,
}

data class TraceEvent(val traceId: String, val requestId: String, val origin: String,
    val sequence: Long, val atMillis: Long, val elapsedMicros: Long,
    val attempt: Int, val stage: String, val fields: Map<String, String>,
    val level: String = RuntimeLogLevel.INFO.name,
    val subsystem: String = "transport",
    val evidenceKind: String? = null,
    val feasibility: String? = null,
    val processRunId: String? = null)

data class RuntimeLogFilter(
    val minimumLevel: RuntimeLogLevel? = null,
    val subsystem: String? = null,
    val requestId: String? = null,
    val search: String? = null,
) {
    fun matches(event: TraceEvent): Boolean {
        if (minimumLevel != null && RuntimeLogLevel.parse(event.level).priority < minimumLevel.priority) return false
        if (!subsystem.isNullOrBlank() && event.subsystem != subsystem) return false
        if (!requestId.isNullOrBlank() && event.requestId != requestId && event.traceId != requestId) return false
        val needle = search?.trim()?.takeIf(String::isNotEmpty)?.lowercase() ?: return true
        return sequenceOf(event.stage, event.level, event.subsystem, event.origin, event.traceId, event.requestId,
            event.processRunId.orEmpty(),
            event.evidenceKind.orEmpty(), event.feasibility.orEmpty(), event.fields.toString())
            .any { needle in it.lowercase() }
    }
}

fun inferredTraceLevel(stage: String, fields: Map<String, String>): RuntimeLogLevel = when {
    stage in setOf("request_failed", "http_rejected", "stream_rejected", "attempt_failed") -> RuntimeLogLevel.ERROR
    stage in setOf("request_cancelled", "resolution_decision") && fields["decision"] == "STOP" -> RuntimeLogLevel.WARN
    stage in setOf("request_cancelled", "resolution_decision") -> RuntimeLogLevel.WARN
    stage.endsWith("completed") || stage == "protocol_validated" -> RuntimeLogLevel.INFO
    else -> RuntimeLogLevel.DEBUG
}

fun inferredEvidenceKind(stage: String): EvidenceKind = when {
    stage in setOf("attempt_start", "request_body_write_start") -> EvidenceKind.ACTUAL_ATTEMPT
    stage in setOf("request_completed", "request_failed", "request_cancelled", "response_body_read", "http_rejected") -> EvidenceKind.RESULT
    stage in setOf("resolution_decision", "candidate_planned") -> EvidenceKind.PREFLIGHT
    else -> EvidenceKind.PLATFORM_RESPONSE
}

fun interface TraceSink { fun record(event: TraceEvent) }
object NoTrace : TraceSink { override fun record(event: TraceEvent) = Unit }
data class RequestContext(
    val requestId: String = UUID.randomUUID().toString(),
    val origin: String = "unspecified",
    val subsystem: String = "transport",
)

/** One trace per upstream round, one parent request per user task. Clock changes cannot alter durations. */
class RequestTrace(private val sink: TraceSink, val context: RequestContext,
    val id: String = UUID.randomUUID().toString()) {
    private val started = System.nanoTime()
    private val sequence = AtomicLong()
    fun event(stage: String, fields: Map<String, String> = emptyMap(), attempt: Int = 0,
        level: RuntimeLogLevel = inferredTraceLevel(stage, fields), evidenceKind: EvidenceKind? = inferredEvidenceKind(stage),
        feasibility: CapabilityFeasibility? = null) {
        sink.record(TraceEvent(id, context.requestId, context.origin, sequence.incrementAndGet(),
            System.currentTimeMillis(), (System.nanoTime() - started) / 1000L, attempt, stage, fields,
            level.name, context.subsystem, evidenceKind?.name, feasibility?.name))
    }

}
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
