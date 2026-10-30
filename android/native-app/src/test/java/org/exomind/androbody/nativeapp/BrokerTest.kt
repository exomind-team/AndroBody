package org.exomind.androbody.nativeapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.diagnostics.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrokerTest {
    @Test fun unregisteredAndUnclassifiedSecurityExceptionsNeverClaimPlatformBlock() = runTest {
        val events = mutableListOf<TraceEvent>()
        val runtimeLog = RuntimeEventLogger(TraceSink { events += it }, processRunId = "run")
        val unclassifiedTool = object : BodyTool {
            override val capability = Capability("am_fixture_security_exception", "fixture", "fixture", objectSchema(), Effect.CONTROL, false)
            override suspend fun execute(invocation: Invocation): ToolResult = throw SecurityException("fixture only")
        }
        val boundaryTool = object : BodyTool {
            override val capability = Capability("am_fixture_platform_boundary", "fixture", "fixture", objectSchema(), Effect.CONTROL, false)
            override suspend fun execute(invocation: Invocation): ToolResult =
                platformCall("fixture.AndroidPermissionCheck") { throw SecurityException("fixture only") }
        }
        val broker = CapabilityBroker(ToolRegistry(listOf(unclassifiedTool, boundaryTool)), object : PermissionPolicy {
            override fun denial(principal: Principal, capability: Capability): String? = null
        }, ApprovalPort { _, _ -> true }, memoryJournal(), runtimeLog = runtimeLog)
        val principal = Principal("native", Origin.NATIVE)

        val missing = broker.invoke(Invocation("request-missing", "call-missing", principal, "am_unregistered", JSONObject()))
        val unclassified = broker.invoke(Invocation("request-unclassified", "call-unclassified", principal,
            "am_fixture_security_exception", JSONObject()))
        val blockedFixture = broker.invoke(Invocation("request-boundary", "call-boundary", principal,
            "am_fixture_platform_boundary", JSONObject()))

        assertEquals(Outcome.UNSUPPORTED, missing.outcome)
        assertEquals(Outcome.UNKNOWN, unclassified.outcome)
        assertEquals(Outcome.DENIED, blockedFixture.outcome)
        assertTrue(events.any { it.stage == "tool_not_registered" && it.feasibility == CapabilityFeasibility.NOT_IMPLEMENTED.name })
        assertFalse(events.any { it.requestId == "request-missing" && it.feasibility == CapabilityFeasibility.BLOCKED_OBSERVED.name })
        assertTrue(events.any { it.requestId == "request-unclassified" && it.stage == "security_exception_source_unverified" &&
            it.feasibility == CapabilityFeasibility.UNKNOWN.name })
        assertFalse(events.any { it.requestId == "request-unclassified" && it.feasibility == CapabilityFeasibility.BLOCKED_OBSERVED.name })
        val boundaryEvent = events.single { it.requestId == "request-boundary" && it.stage == "android_call_blocked" }
        assertEquals(EvidenceKind.PLATFORM_RESPONSE.name, boundaryEvent.evidenceKind)
        assertEquals(CapabilityFeasibility.BLOCKED_OBSERVED.name, boundaryEvent.feasibility)
        assertEquals("fixture.AndroidPermissionCheck", boundaryEvent.fields["platformApi"])
        assertEquals("ANDROID_SECURITY_EXCEPTION", boundaryEvent.fields["reasonCode"])
    }

    @Test fun permissionIsRecheckedAfterHumanApproval() = runTest {
        var revoked = false; var executed = false
        val tool = object : BodyTool {
            override val capability = Capability("am_write", "write", "test", objectSchema(), Effect.WRITE_WORKSPACE, false, true)
            override suspend fun execute(invocation: Invocation): ToolResult { executed = true; return ToolResult.completed(JSONObject(), "write") }
        }
        val broker = CapabilityBroker(ToolRegistry(listOf(tool)), object : PermissionPolicy {
            override fun denial(principal: Principal, capability: Capability) = if (revoked) "revoked" else null
        }, ApprovalPort { _, _ -> revoked = true; true }, memoryJournal())
        val result = broker.invoke(Invocation("r", "c", Principal("native", Origin.NATIVE), "am_write", JSONObject()))
        assertEquals(Outcome.DENIED, result.outcome); assertFalse(executed)
    }
    @Test fun cancellationOfSideEffectStaysUnknown() = runTest {
        val entered = CompletableDeferred<Unit>(); var last: Receipt? = null
        val tool = object : BodyTool {
            override val capability = Capability("am_write", "write", "test", objectSchema(), Effect.WRITE_WORKSPACE, false)
            override suspend fun execute(invocation: Invocation): ToolResult { entered.complete(Unit); awaitCancellation() }
        }
        val broker = CapabilityBroker(ToolRegistry(listOf(tool)), object : PermissionPolicy {
            override fun denial(principal: Principal, capability: Capability): String? = null
        }, ApprovalPort { _, _ -> true }, object : ReceiptJournal {
            override fun begin(invocation: Invocation) = Unit
            override fun finish(invocation: Invocation, receipt: Receipt) { last = receipt }
        })
        val job = launch { broker.invoke(Invocation("r", "c", Principal("native", Origin.NATIVE), "am_write", JSONObject())) }
        entered.await(); job.cancelAndJoin()
        assertEquals(Outcome.UNKNOWN, last?.outcome)
    }
    private fun memoryJournal() = object : ReceiptJournal {
        override fun begin(invocation: Invocation) = Unit
        override fun finish(invocation: Invocation, receipt: Receipt) = Unit
    }
}
