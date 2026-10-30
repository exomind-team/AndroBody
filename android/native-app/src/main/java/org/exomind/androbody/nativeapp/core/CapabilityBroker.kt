package org.exomind.androbody.nativeapp.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.exomind.androbody.nativeapp.diagnostics.CapabilityFeasibility
import org.exomind.androbody.nativeapp.diagnostics.EvidenceKind
import org.exomind.androbody.nativeapp.diagnostics.RuntimeEventLogger
import org.exomind.androbody.nativeapp.diagnostics.RuntimeLogLevel

/** Both runtimes reach the same execution boundary. Grants are rechecked after approval. */
class CapabilityBroker(
    val registry: ToolRegistry,
    private val policy: PermissionPolicy,
    private val approvals: ApprovalPort,
    private val journal: ReceiptJournal,
    private val now: () -> Long = System::currentTimeMillis,
    private val runtimeLog: RuntimeEventLogger? = null,
) {
    fun visibleTo(principal: Principal, requestId: String? = null): List<Capability> {
        val visible = registry.capabilities.filter {
            policy.denial(principal, it) == null &&
                (principal.origin != Origin.GATEWAY || it.gatewayEligible)
        }
        runtimeLog?.record("capability", "tool_catalog_advertised", requestId,
            principal.origin.name.lowercase(), RuntimeLogLevel.DEBUG, EvidenceKind.IMPLEMENTATION,
            CapabilityFeasibility.NOT_ASSESSED, mapOf(
                "registeredCount" to registry.capabilities.size.toString(),
                "advertisedCount" to visible.size.toString(),
                "advertisedTools" to visible.joinToString(",") { it.name },
                "catalogScope" to "registered adapters only; theoretical inventory is open-world",
            ))
        return visible
    }

    /** A model tool request is evidence of an attempted Agent use, never proof that Android was called. */
    fun recordUnadvertisedToolRequest(requestId: String, callId: String, principal: Principal, name: String) {
        val capability = registry.find(name)?.capability
        val denial = capability?.let { policy.denial(principal, it) }
        val feasibility = when {
            capability == null -> CapabilityFeasibility.NOT_IMPLEMENTED
            denial != null -> CapabilityFeasibility.POLICY_DENIED
            else -> CapabilityFeasibility.NOT_ASSESSED
        }
        runtimeLog?.record("capability", "tool_not_advertised", requestId, principal.origin.name.lowercase(),
            RuntimeLogLevel.WARN, EvidenceKind.AGENT_REQUEST, feasibility,
            mapOf("requestedCapability" to name, "callId" to callId,
                "adapterRegistered" to (capability != null).toString(),
                "reasonCode" to denial?.let(::policyReasonCode).orEmpty(),
                "platformCallAttempted" to "false"))
    }

    suspend fun invoke(invocation: Invocation): Receipt {
        val startedAtNanos = System.nanoTime()
        val requestFields = mapOf(
            "capability" to invocation.capability,
            "callId" to invocation.callId,
            "callerOrigin" to invocation.principal.origin.name,
            "argumentNames" to invocation.arguments.keys().asSequence().sorted().joinToString(","),
        )
        runtimeLog?.record("capability", "tool_request_received", invocation.requestId,
            invocation.principal.origin.name.lowercase(), RuntimeLogLevel.INFO,
            EvidenceKind.AGENT_REQUEST, CapabilityFeasibility.NOT_ASSESSED, requestFields)
        val tool = registry.find(invocation.capability)
            ?: return receipt(invocation, Outcome.UNSUPPORTED, "未注册的能力").also {
                runtimeLog?.record("capability", "tool_not_registered", invocation.requestId,
                    invocation.principal.origin.name.lowercase(), RuntimeLogLevel.WARN,
                    EvidenceKind.AGENT_REQUEST, CapabilityFeasibility.NOT_IMPLEMENTED, requestFields)
            }
        runtimeLog?.record("capability", "tool_adapter_found", invocation.requestId,
            invocation.principal.origin.name.lowercase(), RuntimeLogLevel.DEBUG,
            EvidenceKind.IMPLEMENTATION, CapabilityFeasibility.NOT_ASSESSED,
            requestFields + ("effect" to tool.capability.effect.name) + ("gatewayEligible" to tool.capability.gatewayEligible.toString()))
        try {
            journal.begin(invocation) // If durable intent cannot be written, do not execute.
        } catch (failure: Exception) {
            runtimeLog?.record("capability", "receipt_begin_failed", invocation.requestId,
                invocation.principal.origin.name.lowercase(), RuntimeLogLevel.ERROR,
                EvidenceKind.PREFLIGHT, CapabilityFeasibility.UNKNOWN,
                requestFields + ("exceptionClass" to failure.javaClass.name))
            throw failure
        }
        var toolExecutionStarted = false
        val result = try {
            val denied = policy.denial(invocation.principal, tool.capability)
            when {
                denied != null -> {
                    runtimeLog?.record("capability", "tool_policy_denied", invocation.requestId,
                        invocation.principal.origin.name.lowercase(), RuntimeLogLevel.WARN,
                        EvidenceKind.PREFLIGHT, CapabilityFeasibility.POLICY_DENIED,
                        requestFields + ("reasonCode" to policyReasonCode(denied)))
                    receipt(invocation, Outcome.DENIED, denied)
                }
                else -> {
                    validateArguments(tool.capability, invocation.arguments)
                    val approvalRequired = policy.requiresApproval(invocation.principal, tool.capability)
                    if (approvalRequired) runtimeLog?.record("capability", "user_approval_requested", invocation.requestId,
                        invocation.principal.origin.name.lowercase(), RuntimeLogLevel.INFO,
                        EvidenceKind.PREFLIGHT, CapabilityFeasibility.NEEDS_USER_ACTION, requestFields)
                    if (approvalRequired && !approvals.approve(invocation, tool.capability)) {
                        runtimeLog?.record("capability", "user_approval_denied", invocation.requestId,
                            invocation.principal.origin.name.lowercase(), RuntimeLogLevel.WARN,
                            EvidenceKind.PREFLIGHT, CapabilityFeasibility.USER_DENIED, requestFields)
                        receipt(invocation, Outcome.DENIED, "本次操作未获确认")
                    } else {
                        runtimeLog?.record("capability", if (approvalRequired) "user_approval_granted" else "tool_policy_allowed",
                            invocation.requestId, invocation.principal.origin.name.lowercase(), RuntimeLogLevel.INFO,
                            EvidenceKind.PREFLIGHT, CapabilityFeasibility.NOT_ASSESSED,
                            requestFields + ("approvalRequired" to approvalRequired.toString()))
                        currentCoroutineContext().ensureActive()
                        val revoked = policy.denial(invocation.principal, tool.capability)
                        if (revoked != null) {
                            runtimeLog?.record("capability", "tool_policy_revoked_after_approval", invocation.requestId,
                                invocation.principal.origin.name.lowercase(), RuntimeLogLevel.WARN,
                                EvidenceKind.PREFLIGHT, CapabilityFeasibility.POLICY_DENIED,
                                requestFields + ("reasonCode" to policyReasonCode(revoked)))
                            receipt(invocation, Outcome.DENIED, revoked)
                        }
                        else {
                            toolExecutionStarted = true
                            runtimeLog?.record("capability", "tool_execution_started", invocation.requestId,
                                invocation.principal.origin.name.lowercase(), RuntimeLogLevel.INFO,
                                EvidenceKind.ACTUAL_ATTEMPT, CapabilityFeasibility.NOT_ASSESSED,
                                requestFields + ("attemptBoundary" to "BodyTool.execute"))
                            val execution = tool.execute(invocation)
                            Receipt(invocationId = invocation.id, requestId = invocation.requestId,
                                capability = invocation.capability, outcome = execution.outcome,
                                observedAt = now(), data = execution.data, evidence = execution.evidence)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            val status = if (tool.capability.effect == Effect.READ_DEVICE || tool.capability.effect == Effect.READ_WORKSPACE)
                Outcome.CANCELLED else Outcome.UNKNOWN
            runtimeLog?.record("capability", "tool_execution_cancelled", invocation.requestId,
                invocation.principal.origin.name.lowercase(), RuntimeLogLevel.WARN,
                EvidenceKind.ACTUAL_ATTEMPT, CapabilityFeasibility.UNKNOWN,
                requestFields + ("outcome" to status.name) + ("durationMs" to elapsedMs(startedAtNanos).toString()))
            try { journal.finish(invocation, receipt(invocation, status, "执行等待已取消；已发生的副作用不会自动撤销")) }
            catch (storageFailure: Exception) { cancelled.addSuppressed(storageFailure) }
            throw cancelled
        } catch (failure: BodyFailure) {
            val feasibility = when {
                failure.code == "NEEDS_GRANT" -> CapabilityFeasibility.NEEDS_PERMISSION
                toolExecutionStarted -> CapabilityFeasibility.FAILED
                else -> CapabilityFeasibility.NOT_ASSESSED
            }
            runtimeLog?.record("capability", "tool_execution_failed", invocation.requestId,
                invocation.principal.origin.name.lowercase(), RuntimeLogLevel.WARN,
                if (failure.code == "NEEDS_GRANT" || !toolExecutionStarted) EvidenceKind.PREFLIGHT else EvidenceKind.RESULT,
                feasibility, requestFields + ("errorCode" to failure.code))
            receipt(invocation, Outcome.FAILED, "${failure.code}: ${failure.safeDetail}")
        } catch (failure: PlatformCallDenied) {
            runtimeLog?.record("capability", "android_call_blocked", invocation.requestId,
                invocation.principal.origin.name.lowercase(), RuntimeLogLevel.ERROR,
                EvidenceKind.PLATFORM_RESPONSE, CapabilityFeasibility.BLOCKED_OBSERVED,
                requestFields + ("platformApi" to failure.api.take(80)) +
                    ("reasonCode" to failure.reasonCode.take(64)) +
                    ("exceptionClass" to (failure.cause?.javaClass?.name ?: "SecurityException")))
            receipt(invocation, Outcome.DENIED, "平台在已标记的调用边界拒绝了本次操作")
        } catch (failure: SecurityException) {
            val outcome = if (tool.capability.effect in setOf(Effect.READ_DEVICE, Effect.READ_WORKSPACE))
                Outcome.FAILED else Outcome.UNKNOWN
            runtimeLog?.record("capability", "security_exception_source_unverified", invocation.requestId,
                invocation.principal.origin.name.lowercase(), RuntimeLogLevel.ERROR,
                EvidenceKind.RESULT, CapabilityFeasibility.UNKNOWN,
                requestFields + ("exceptionClass" to failure.javaClass.name) + ("outcome" to outcome.name))
            receipt(invocation, outcome, "捕获到 SecurityException，但适配器未标注平台调用边界；拒绝来源未核实")
        } catch (failure: Exception) {
            runtimeLog?.record("capability", "tool_execution_unknown", invocation.requestId,
                invocation.principal.origin.name.lowercase(), RuntimeLogLevel.ERROR,
                EvidenceKind.ACTUAL_ATTEMPT, CapabilityFeasibility.UNKNOWN,
                requestFields + ("exceptionClass" to failure.javaClass.name) +
                    ("causeClass" to (failure.cause?.javaClass?.name ?: "")))
            receipt(invocation, if (tool.capability.effect in setOf(Effect.READ_DEVICE, Effect.READ_WORKSPACE)) Outcome.FAILED else Outcome.UNKNOWN,
                "执行器未返回最终证据；可能存在未确认效果，请核查而非重放")
        }
        // Journal failure is not executor failure. Leave the durable RUNNING intent to recover as UNKNOWN.
        return complete(invocation, result, startedAtNanos)
    }

    private fun complete(invocation: Invocation, receipt: Receipt, startedAtNanos: Long): Receipt {
        try {
            journal.finish(invocation, receipt)
        } catch (failure: Exception) {
            runtimeLog?.record("capability", "receipt_finish_failed", invocation.requestId,
                invocation.principal.origin.name.lowercase(), RuntimeLogLevel.ERROR,
                EvidenceKind.RECOVERY, CapabilityFeasibility.UNKNOWN,
                mapOf("capability" to invocation.capability, "callId" to invocation.callId,
                    "outcome" to receipt.outcome.name, "exceptionClass" to failure.javaClass.name))
            throw failure
        }
        runtimeLog?.record("capability", "tool_receipt_persisted", invocation.requestId,
            invocation.principal.origin.name.lowercase(), RuntimeLogLevel.INFO,
            EvidenceKind.RESULT, feasibilityFor(receipt.outcome), mapOf("capability" to invocation.capability,
                "callId" to invocation.callId, "invocationId" to invocation.id, "outcome" to receipt.outcome.name,
                "durationMs" to elapsedMs(startedAtNanos).toString(),
                "resultFields" to receipt.data.keys().asSequence().sorted().joinToString(","),
                "resultBytes" to receipt.data.toString().toByteArray(Charsets.UTF_8).size.toString()))
        return receipt
    }

    private fun elapsedMs(startedAtNanos: Long): Long = ((System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L)

    private fun policyReasonCode(reason: String): String = when {
        "身份已撤销" in reason -> "IDENTITY_REVOKED"
        "未共享" in reason -> "GATEWAY_SCOPE_MISSING"
        "拒绝" in reason -> "USER_POLICY_DENIED"
        else -> "POLICY_DENIED"
    }

    private fun feasibilityFor(outcome: Outcome): CapabilityFeasibility = when (outcome) {
        Outcome.SUBMITTED -> CapabilityFeasibility.SUBMITTED
        Outcome.COMPLETED -> CapabilityFeasibility.COMPLETED
        Outcome.DENIED -> CapabilityFeasibility.NOT_ASSESSED
        Outcome.UNSUPPORTED -> CapabilityFeasibility.UNAVAILABLE
        Outcome.CANCELLED -> CapabilityFeasibility.UNKNOWN
        Outcome.UNKNOWN -> CapabilityFeasibility.UNKNOWN
        Outcome.FAILED -> CapabilityFeasibility.FAILED
    }
    private fun receipt(invocation: Invocation, status: Outcome, evidence: String) =
        Receipt(invocationId = invocation.id, requestId = invocation.requestId, capability = invocation.capability, outcome = status, observedAt = now(), evidence = evidence)

    private fun validateArguments(capability: Capability, arguments: org.json.JSONObject) {
        val properties = capability.parameters.getJSONObject("properties")
        val keys = arguments.keys().asSequence().toList()
        if (keys.any { !properties.has(it) }) throw BodyFailure("INVALID_ARGUMENT", "包含未定义字段")
        val required = capability.parameters.getJSONArray("required")
        for (i in 0 until required.length()) {
            if (!arguments.has(required.getString(i))) throw BodyFailure("INVALID_ARGUMENT", "缺少必需字段")
        }
        for (key in keys) {
            val schema = properties.getJSONObject(key)
            val value = arguments.get(key)
            when (schema.optString("type")) {
                "string" -> if (value !is String || value.length > schema.optInt("maxLength", Int.MAX_VALUE))
                    throw BodyFailure("INVALID_ARGUMENT", "文本字段类型或长度错误：$key")
                "integer" -> if (value !is Number || value.toDouble() != value.toLong().toDouble() ||
                    value.toDouble() < schema.optDouble("minimum", -Double.MAX_VALUE) ||
                    value.toDouble() > schema.optDouble("maximum", Double.MAX_VALUE))
                    throw BodyFailure("INVALID_ARGUMENT", "整数字段超出范围：$key")
                "boolean" -> if (value !is Boolean) throw BodyFailure("INVALID_ARGUMENT", "布尔字段错误：$key")
            }
        }
    }
}
