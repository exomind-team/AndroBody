package org.exomind.androbody.nativeapp.model

import org.exomind.androbody.nativeapp.config.ModelProtocol
import org.exomind.androbody.nativeapp.core.WireFailure
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/** Reconstructs one complete protocol response while exposing only text deltas to the UI. */
internal class SseModelAssembler(
    private val protocol: ModelProtocol,
    private val onText: (String) -> Unit,
) {
    val isComplete: Boolean get() = ended
    private var eventName: String? = null
    private val data = StringBuilder()
    private var ended = false
    private val openAiText = StringBuilder()
    private val openAiTools = TreeMap<Int, OpenAiToolDelta>()
    private var openAiFinishReason: String? = null
    private var openAiModel: String? = null
    private var openAiUsage: JSONObject? = null
    private var anthropicId: String? = null
    private var anthropicModel: String? = null
    private var anthropicRole: String = "assistant"
    private var anthropicStopReason: String? = null
    private var anthropicUsage: JSONObject? = null
    private val anthropicBlocks = TreeMap<Int, AnthropicBlockDelta>()

    fun acceptLine(line: String) {
        if (line.isEmpty()) { dispatch(); return }
        if (line.startsWith(":")) return
        val split = line.indexOf(':')
        val field = if (split < 0) line else line.substring(0, split)
        var value = if (split < 0) "" else line.substring(split + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
    }

    fun finish(): JSONObject {
        dispatch()
        if (!ended) throw WireFailure(502, "INCOMPLETE_STREAM", "上游事件流没有正常结束；未执行工具")
        return when (protocol) {
            ModelProtocol.OPENAI -> buildOpenAi()
            ModelProtocol.ANTHROPIC -> buildAnthropic()
        }
    }

    private fun dispatch() {
        val payload = data.toString()
        val name = eventName
        data.setLength(0)
        eventName = null
        if (payload.isBlank()) return
        if (ended) throw WireFailure(502, "INVALID_STREAM", "终止事件后仍收到数据")
        if (name == "error") throw WireFailure(502, "UPSTREAM_STREAM_ERROR", "上游在事件流中返回错误")
        when (protocol) {
            ModelProtocol.OPENAI -> consumeOpenAi(payload)
            ModelProtocol.ANTHROPIC -> consumeAnthropic(payload, name)
        }
    }

    private fun consumeOpenAi(payload: String) {
        if (payload == "[DONE]") { ended = true; return }
        val value = try { JSONObject(payload) } catch (_: Exception) {
            throw WireFailure(502, "INVALID_STREAM", "OpenAI 事件不是有效 JSON")
        }
        if (value.optJSONObject("error") != null)
            throw WireFailure(502, "UPSTREAM_STREAM_ERROR", "上游在事件流中返回错误")
        value.optString("model").takeIf { it.isNotBlank() }?.let { openAiModel = it }
        value.optJSONObject("usage")?.let { openAiUsage = JSONObject(it.toString()) }
        val choices = value.optJSONArray("choices") ?: return
        if (choices.length() == 0) return
        if (choices.length() != 1) throw WireFailure(422, "CHOICE_COUNT_UNSUPPORTED", "仅支持一个候选答案")
        val choice = choices.optJSONObject(0) ?: throw WireFailure(502, "INVALID_STREAM", "OpenAI 候选格式错误")
        choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { openAiFinishReason = it }
        val delta = choice.optJSONObject("delta") ?: return
        val content = delta.opt("content")
        if (content is String && content.isNotEmpty()) {
            openAiText.append(content)
            onText(content)
        }
        val refusal = delta.opt("refusal")
        if (refusal is String && refusal.isNotEmpty()) {
            openAiText.append(refusal)
            onText(refusal)
        }
        val calls = delta.optJSONArray("tool_calls") ?: return
        for (index in 0 until calls.length()) {
            val item = calls.optJSONObject(index) ?: continue
            val callIndex = item.optInt("index", index)
            if (callIndex < 0) throw WireFailure(422, "INVALID_TOOL_INDEX", "流式工具调用索引不能为负数")
            val target = openAiTools.getOrPut(callIndex) { OpenAiToolDelta() }
            item.optString("id").takeIf { it.isNotBlank() && it != "null" }?.let { target.id.append(it) }
            item.optString("type").takeIf { it.isNotBlank() && it != "null" }?.let { target.type = it }
            item.optJSONObject("function")?.let { function ->
                function.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { target.name.append(it) }
                function.optString("arguments").takeIf { it.isNotBlank() && it != "null" }?.let { target.arguments.append(it) }
            }
        }
        if (delta.has("function_call"))
            throw WireFailure(422, "LEGACY_FUNCTION_CALL_UNSUPPORTED", "不支持旧式 function_call 流")
    }

    private fun consumeAnthropic(payload: String, eventType: String?) {
        val value = try { JSONObject(payload) } catch (_: Exception) {
            throw WireFailure(502, "INVALID_STREAM", "Anthropic 事件不是有效 JSON")
        }
        when (value.optString("type", eventType.orEmpty())) {
            "message_start" -> {
                val message = value.optJSONObject("message") ?: return
                anthropicId = message.optString("id").takeIf(String::isNotBlank)
                anthropicModel = message.optString("model").takeIf(String::isNotBlank)
                anthropicRole = message.optString("role", "assistant")
                mergeUsage(message.optJSONObject("usage"))
            }
            "content_block_start" -> {
                val index = value.optInt("index", -1)
                val block = value.optJSONObject("content_block")
                if (index < 0 || block == null) throw WireFailure(502, "INVALID_STREAM", "Anthropic 内容块缺少标识")
                anthropicBlocks[index] = AnthropicBlockDelta(block.optString("type"), JSONObject(block.toString()))
            }
            "content_block_delta" -> {
                val index = value.optInt("index", -1)
                val block = anthropicBlocks[index] ?: throw WireFailure(502, "INVALID_STREAM", "Anthropic 增量没有对应内容块")
                val delta = value.optJSONObject("delta") ?: return
                when (delta.optString("type")) {
                    "text_delta" -> delta.optString("text").takeIf(String::isNotEmpty)?.let {
                        block.text.append(it); onText(it)
                    }
                    "input_json_delta" -> delta.optString("partial_json").takeIf(String::isNotEmpty)?.let { block.partialJson.append(it) }
                    "thinking_delta" -> delta.optString("thinking").takeIf(String::isNotEmpty)?.let { block.thinking.append(it) }
                    "signature_delta" -> delta.optString("signature").takeIf(String::isNotBlank)?.let { block.signature = it }
                }
            }
            "content_block_stop" -> {
                val index = value.optInt("index", -1)
                val block = anthropicBlocks[index] ?: throw WireFailure(502, "INVALID_STREAM", "Anthropic 结束了未知内容块")
                block.closed = true
            }
            "message_delta" -> {
                value.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() && it != "null" }
                    ?.let { anthropicStopReason = it }
                mergeUsage(value.optJSONObject("usage"))
            }
            "message_stop" -> ended = true
            "ping" -> Unit
            "error" -> throw WireFailure(502, "UPSTREAM_STREAM_ERROR", "Anthropic 在事件流中返回错误")
            else -> Unit // The protocol allows new non-content event types.
        }
    }

    private fun mergeUsage(next: JSONObject?) {
        if (next == null) return
        val merged = JSONObject(anthropicUsage?.toString() ?: "{}")
        next.keys().forEach { key -> merged.put(key, next.get(key)) }
        anthropicUsage = merged
    }

    private fun buildOpenAi(): JSONObject {
        val finish = openAiFinishReason ?: throw WireFailure(502, "INCOMPLETE_STREAM", "OpenAI 流缺少 finish_reason")
        val message = JSONObject().put("role", "assistant").put("content", openAiText.toString())
        if (openAiTools.isNotEmpty()) {
            val calls = JSONArray()
            openAiTools.forEach { (index, tool) ->
                if (tool.id.isEmpty() || tool.name.isEmpty())
                    throw WireFailure(502, "INVALID_CALL_ID", "流式工具调用缺少完整标识")
                val arguments = if (tool.arguments.isEmpty()) "{}" else tool.arguments.toString()
                try { JSONObject(arguments) } catch (_: Exception) {
                    throw WireFailure(502, "INVALID_TOOL_ARGUMENTS", "流式工具参数不是完整 JSON；未执行")
                }
                calls.put(JSONObject().put("index", index).put("id", tool.id.toString())
                    .put("type", tool.type).put("function", JSONObject()
                        .put("name", tool.name.toString()).put("arguments", arguments)))
            }
            message.put("tool_calls", calls)
        }
        val choice = JSONObject().put("index", 0).put("message", message).put("finish_reason", finish)
        val response = JSONObject().put("choices", JSONArray().put(choice))
        openAiModel?.let { response.put("model", it) }
        openAiUsage?.let { response.put("usage", it) }
        return response
    }

    private fun buildAnthropic(): JSONObject {
        val stop = anthropicStopReason ?: throw WireFailure(502, "INCOMPLETE_STREAM", "Anthropic 流缺少 stop_reason")
        if (anthropicBlocks.values.any { !it.closed })
            throw WireFailure(502, "INCOMPLETE_STREAM", "Anthropic 流未关闭所有内容块；未执行工具")
        val content = JSONArray()
        anthropicBlocks.values.forEach { content.put(it.toJson()) }
        val response = JSONObject().put("type", "message").put("role", anthropicRole)
            .put("content", content).put("stop_reason", stop)
        anthropicId?.let { response.put("id", it) }
        anthropicModel?.let { response.put("model", it) }
        anthropicUsage?.let { response.put("usage", it) }
        return response
    }

    private class OpenAiToolDelta {
        val id = StringBuilder()
        var type = "function"
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private class AnthropicBlockDelta(val type: String, private val source: JSONObject) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val partialJson = StringBuilder()
        var signature: String? = null
        var closed = false

        fun toJson(): JSONObject = when (type) {
            "text" -> JSONObject().put("type", "text").put("text", text.toString())
            "tool_use" -> {
                val argumentText = partialJson.toString()
                val input = if (argumentText.isBlank()) source.optJSONObject("input") ?: JSONObject()
                    else try { JSONObject(argumentText) } catch (_: Exception) {
                        throw WireFailure(502, "INVALID_TOOL_ARGUMENTS", "Anthropic 流式工具参数不是完整 JSON；未执行")
                    }
                JSONObject().put("type", "tool_use").put("id", source.optString("id"))
                    .put("name", source.optString("name")).put("input", input)
            }
            "thinking" -> JSONObject().put("type", "thinking").put("thinking", thinking.toString())
                .put("signature", signature ?: source.optString("signature"))
            else -> JSONObject(source.toString())
        }
    }
}
