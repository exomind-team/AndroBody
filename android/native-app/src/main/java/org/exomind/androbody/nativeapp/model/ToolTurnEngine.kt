package org.exomind.androbody.nativeapp.model

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.core.*
import org.json.JSONArray
import org.json.JSONObject

fun interface ModelTransport { suspend fun call(request: JSONObject): JSONObject }
fun interface ModelStreamTransport {
    suspend fun call(request: JSONObject, onText: (String) -> Unit): JSONObject
}
data class TurnReply(val response: JSONObject, val messages: JSONArray, val rounds: Int, val receiptIds: List<String>,
    val usageTotals: Map<String, Long>, val reportedInputUsage: ReportedInputUsage? = null)

/** A cancellable, checkpointed continuation, not a second host Session/agent scheduler. */
class ToolTurnEngine(private val broker: CapabilityBroker) {
    fun prepare(original: JSONObject, wire: ModelWire, principal: Principal, settings: ModelSettings,
        forTokenCount: Boolean = false, streaming: Boolean = false, requestId: String? = null,
        allowedToolNames: Set<String>? = null): JSONObject {
        val request = original.copyJson()
        if (request.optInt("n", 1) != 1) throw WireFailure(422, "MULTI_CHOICE_UNSUPPORTED", "仅支持 n=1")
        if (request.has("previous_response_id") || request.has("conversation")) throw WireFailure(422, "PROTOCOL_UNSUPPORTED", "当前不支持 Responses 会话协议")
        val model = request.optString("model")
        if (model != settings.model && model != "andromorph") throw WireFailure(400, "MODEL_NOT_CONFIGURED", "请使用本机配置的模型名或 andromorph 别名")
        request.put("model", settings.model)
        if (request.optJSONArray("messages") == null) throw WireFailure(400, "MESSAGES_REQUIRED", "缺少 messages")
        request.put("stream", streaming)
        request.remove("stream_options")
        val tokenField = if (wire.protocol == ModelProtocol.OPENAI && request.has("max_completion_tokens")) "max_completion_tokens" else "max_tokens"
        val suppliedTokens = request.optInt(tokenField, 0)
        val effectiveTokens = when {
            settings.maxTokens > 0 && suppliedTokens > 0 -> suppliedTokens.coerceAtMost(settings.maxTokens)
            settings.maxTokens > 0 -> settings.maxTokens
            suppliedTokens > 0 -> suppliedTokens
            wire.protocol == ModelProtocol.ANTHROPIC && !forTokenCount -> DEFAULT_ANTHROPIC_TOKENS
            else -> 0 // Chat Completions leaves the choice to the upstream unless the user sets a cap.
        }
        if (effectiveTokens > 0) request.put(tokenField, effectiveTokens) else request.remove(tokenField)
        val thinkingBudget = request.optJSONObject("thinking")?.optInt("budget_tokens", 0) ?: 0
        if (!forTokenCount && effectiveTokens > 0 && thinkingBudget >= effectiveTokens)
            throw WireFailure(422, "THINKING_BUDGET", "思考预算超过本次输出上限")
        val originalTools = request.optJSONArray("tools") ?: JSONArray()
        val names = originalTools.objects().mapNotNull {
            if (wire.protocol == ModelProtocol.OPENAI) it.optJSONObject("function")?.optString("name") else it.optString("name").takeIf(String::isNotBlank)
        }
        if (names.any { it.startsWith("am_") }) throw WireFailure(409, "TOOL_NAMESPACE_COLLISION", "客户端工具使用了保留的 am_ 前缀")
        val choice = request.opt("tool_choice")
        val none = choice == "none" || (choice is JSONObject && choice.optString("type") == "none")
        if (!none) {
            val candidates = if (principal.origin == Origin.EVENT) emptyList() else broker.visibleTo(principal, requestId)
                .filter { allowedToolNames == null || it.name in allowedToolNames }
            wire.definitions(candidates.sortedBy { it.name }).forEach(originalTools::put)
            if (originalTools.length() > 0) request.put("tools", originalTools)
        }
        return request
    }

    suspend fun run(original: JSONObject, wire: ModelWire, principal: Principal, settings: ModelSettings,
        requestId: String, beforeTool: () -> Unit = {}, streamTransport: ModelStreamTransport? = null,
        onText: (String) -> Unit = {}, allowedToolNames: Set<String>? = null,
        onCheckpoint: (JSONArray, Boolean) -> Unit = { _, _ -> },
        onRequestBudget: (PreparedRequestBudget) -> Unit = {},
        onProviderInputUsage: (ReportedInputUsage?) -> Unit = {},
        transport: ModelTransport): TurnReply = withTimeout(TURN_TIMEOUT_MS) {
        val request = prepare(original, wire, principal, settings, streaming = streamTransport != null,
            requestId = requestId, allowedToolNames = allowedToolNames)
        val ownedNames = (request.optJSONArray("tools") ?: JSONArray()).objects().mapNotNull {
            if (wire.protocol == ModelProtocol.OPENAI) it.optJSONObject("function")?.optString("name") else it.optString("name")
        }.filter { it.startsWith("am_") }.toSet()
        val receiptIds = mutableListOf<String>()
        val usedIds = mutableSetOf<String>()
        val usage = linkedMapOf<String, Long>()
        var reportedInputUsage: ReportedInputUsage? = null
        var rounds = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val requestJson = request.toString()
            val requestBudget = ContextBudgetEstimator.preparedRequest(requestJson)
            onRequestBudget(requestBudget)
            if (requestBudget.serializedBytes > ModelHttpClient.MAX_REQUEST_BYTES)
                throw WireFailure(413, "CONTEXT_BUDGET",
                    "本次请求 ${requestBudget.serializedBytes} 字节，超过 2 MiB 传输上限；未发送，历史未裁剪")
            val response = streamTransport?.call(request, onText) ?: transport.call(request)
            rounds++
            reportedInputUsage = wire.reportedInputUsage(response)
            onProviderInputUsage(reportedInputUsage)
            val rawUsage = response.optJSONObject("usage")
            rawUsage?.keys()?.forEach { key ->
                val value = rawUsage.opt(key)
                if (value is Number) usage[key] = (usage[key] ?: 0) + value.toLong()
            }
            val calls = try { wire.calls(response) } catch (failure: WireFailure) { throw failure }
                catch (_: Exception) { throw WireFailure(502, "INVALID_TOOL_ARGUMENTS", "工具调用参数不是完整可解析对象；未执行") }
            if (calls.any { it.id.isBlank() || it.name.isBlank() } || calls.map { it.id }.distinct().size != calls.size)
                throw WireFailure(502, "INVALID_CALL_ID", "工具调用标识缺失或重复")
            val hidden = calls.filter { it.name in ownedNames }
            val external = calls.filterNot { it.name in ownedNames }
            val unadvertised = calls.filter { it.name.startsWith("am_") && it.name !in ownedNames }
            if (unadvertised.isNotEmpty()) {
                unadvertised.forEach { broker.recordUnadvertisedToolRequest(requestId, it.id, principal, it.name) }
                throw WireFailure(403, "UNADVERTISED_CAPABILITY", "模型请求了未开放的能力")
            }
            if (hidden.isNotEmpty() && external.isNotEmpty())
                throw WireFailure(422, "MIXED_TOOL_OWNERSHIP", "同轮同时包含宿主与网关工具；本轮尚未执行任何网关动作，请拆成顺序调用")
            if (hidden.isEmpty()) {
                if (wire.needsOutputContinuation(response)) {
                    wire.continueOutput(request, response)
                    onCheckpoint(JSONArray(request.getJSONArray("messages").toString()), false)
                    continue
                }
                // Provider usage describes this final turn/context. Summing it into the vendor field
                // would make a host misjudge its remaining context. Totals travel out-of-band.
                val messages = JSONArray(request.getJSONArray("messages").toString()).put(wire.assistantMessage(response))
                return@withTimeout TurnReply(response, messages, rounds, receiptIds, usage.toMap(), reportedInputUsage)
            }
            if (hidden.any { it.id in usedIds }) throw WireFailure(409, "REPLAYED_CALL", "模型重复使用工具调用 ID；未重放动作")
            val results = mutableListOf<Pair<ModelCall, Receipt>>()
            for (call in hidden) {
                usedIds += call.id
                currentCoroutineContext().ensureActive()
                val invocation = Invocation(requestId = requestId, callId = call.id, principal = principal, capability = call.name, arguments = call.arguments)
                beforeTool() // Records that a device execution boundary was approached; never called for HTTP-only failures.
                val receipt = broker.invoke(invocation)
                receiptIds += receipt.invocationId
                results += call to receipt
            }
            wire.continueWith(request, response, results)
            onCheckpoint(JSONArray(request.getJSONArray("messages").toString()), receiptIds.isNotEmpty())
        }
        error("Unreachable tool-turn state")
    }
    companion object {
        // A wall-clock cancellation boundary keeps runaway requests stoppable without dropping work
        // merely because a model returned many valid tool calls or needed several continuation rounds.
        const val TURN_TIMEOUT_MS = 30 * 60_000L
        private const val DEFAULT_ANTHROPIC_TOKENS = 8192
    }
}
