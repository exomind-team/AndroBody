package org.exomind.androbody.nativeapp

import org.exomind.androbody.nativeapp.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeEventLoggerTest {
    @Test fun theoryPreflightAttemptAndResultRemainDistinctAndSensitiveFieldsAreDropped() {
        val events = mutableListOf<TraceEvent>()
        val logger = RuntimeEventLogger(TraceSink { event -> events.add(event); Unit },
            processRunId = "run-1", nowMillis = { 100L }, nowNanos = { 1_000L })

        logger.record("capability", "intent_candidate_seen", "request-1", evidenceKind = EvidenceKind.STATIC_CANDIDATE,
            feasibility = CapabilityFeasibility.BLOCKER_CANDIDATE,
            fields = mapOf("capability" to "am_intent_send", "apiKey" to "must-not-be-stored", "notificationBody" to "private-body"))
        logger.record("intent", "activity_start_blocked", "request-1", level = RuntimeLogLevel.ERROR,
            evidenceKind = EvidenceKind.ACTUAL_ATTEMPT, feasibility = CapabilityFeasibility.BLOCKED_OBSERVED,
            fields = mapOf("action" to "android.intent.action.VIEW", "exceptionClass" to "SecurityException"))

        assertEquals(2, events.size)
        assertEquals(EvidenceKind.STATIC_CANDIDATE.name, events[0].evidenceKind)
        assertEquals(CapabilityFeasibility.BLOCKER_CANDIDATE.name, events[0].feasibility)
        assertFalse(events[0].fields.containsKey("apiKey"))
        assertFalse(events[0].fields.containsKey("notificationBody"))
        assertEquals("run-1", events[1].processRunId)
        assertEquals(EvidenceKind.ACTUAL_ATTEMPT.name, events[1].evidenceKind)
        assertEquals(CapabilityFeasibility.BLOCKED_OBSERVED.name, events[1].feasibility)
    }

    @Test fun queryFiltersUseEvidenceAndFeasibilityWithoutReclassifyingStaticCandidates() {
        val candidate = TraceEvent("candidate", "request-2", "native", 1, 100, 20, 0,
            "intent_candidate_seen", mapOf("capability" to "am_intent_send"), "INFO", "capability",
            EvidenceKind.STATIC_CANDIDATE.name, CapabilityFeasibility.BLOCKER_CANDIDATE.name)
        val blocked = TraceEvent("attempt", "request-2", "native", 2, 101, 21, 0,
            "intent_start_blocked", mapOf("capability" to "am_intent_send"), "ERROR", "intent",
            EvidenceKind.ACTUAL_ATTEMPT.name, CapabilityFeasibility.BLOCKED_OBSERVED.name)

        assertTrue(RuntimeLogFilter(minimumLevel = RuntimeLogLevel.ERROR, requestId = "request-2", search = "BLOCKED")
            .matches(blocked))
        assertFalse(RuntimeLogFilter(minimumLevel = RuntimeLogLevel.ERROR, requestId = "request-2")
            .matches(candidate))
        assertTrue(RuntimeLogFilter(subsystem = "capability", search = "candidate").matches(candidate))
    }

    @Test fun legacyTraceConstructorDefaultsToTransportMetadata() {
        val legacy = TraceEvent("trace", "request", "native", 1, 1, 1, 0, "request_start", emptyMap())
        assertEquals(RuntimeLogLevel.INFO.name, legacy.level)
        assertEquals("transport", legacy.subsystem)
        assertNull(legacy.evidenceKind)
        assertNull(legacy.feasibility)
    }
}
