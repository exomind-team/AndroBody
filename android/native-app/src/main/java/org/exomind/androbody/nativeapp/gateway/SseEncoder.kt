package org.exomind.androbody.nativeapp.gateway

import org.exomind.androbody.nativeapp.config.ModelProtocol
import org.exomind.androbody.nativeapp.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Buffered-final SSE. No fake per-token timing, and no hidden tool fragments escape. */
object SseEncoder {
    fun frames(protocol: ModelProtocol, response: JSONObject, includeUsage: Boolean = false): List<String> =
        when (protocol) { ModelProtocol.ANTHROPIC -> anthropic(response); ModelProtocol.OPENAI -> openai(response, includeUsage) }
    private fun event(name: String, data: JSONObject) = "event: $name\ndata: $data\n\n"
    private fun anthropic(response: JSONObject): List<String> {
        val blocks = response.getJSONArray("content").objects()
        if (blocks.any { it.optString("type") !in setOf("text", "tool_use", "thinking", "redacted_thinking") })
            throw WireFailure(422, "SSE_BLOCK_UNSUPPORTED", "此响应块不在已实现的流式合同内")
        val frames = mutableListOf<String>()
        val usage = response.optJSONObject("usage") ?: JSONObject()
        val initial = response.copyJson().put("content", JSONArray()).put("stop_reason", JSONObject.NULL).put("stop_sequence", JSONObject.NULL)
            .put("usage", usage.copyJson().put("output_tokens", 0))
        frames += event("message_start", jsonObject("type" to "message_start", "message" to initial))
        blocks.forEachIndexed { index, block ->
            val start = block.copyJson()
            val deltas = mutableListOf<JSONObject>()
            when (block.getString("type")) {
                "text" -> {
                    start.put("text", "")
                    deltas += jsonObject("type" to "text_delta", "text" to block.getString("text"))
                }
                "tool_use" -> {
                    start.put("input", JSONObject())
                    deltas += jsonObject("type" to "input_json_delta", "partial_json" to block.getJSONObject("input").toString())
                }
                "thinking" -> {
                    start.put("thinking", ""); start.put("signature", "")
                    deltas += jsonObject("type" to "thinking_delta", "thinking" to block.optString("thinking"))
                    deltas += jsonObject("type" to "signature_delta", "signature" to block.getString("signature"))
                }
                "redacted_thinking" -> Unit // opaque content is preserved in the start block.
            }
            frames += event("content_block_start", jsonObject("type" to "content_block_start", "index" to index, "content_block" to start))
            deltas.forEach { frames += event("content_block_delta", jsonObject("type" to "content_block_delta", "index" to index, "delta" to it)) }
            frames += event("content_block_stop", jsonObject("type" to "content_block_stop", "index" to index))
        }
        frames += event("message_delta", jsonObject("type" to "message_delta", "delta" to jsonObject("stop_reason" to response.opt("stop_reason"),
            "stop_sequence" to response.opt("stop_sequence")), "usage" to jsonObject("output_tokens" to usage.optLong("output_tokens", 0))))
        frames += event("message_stop", jsonObject("type" to "message_stop"))
        return frames
    }
    private fun openai(response: JSONObject, includeUsage: Boolean): List<String> {
        val choice = response.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")
        fun chunk(delta: JSONObject, finish: Any? = null): String {
            val chunk = jsonObject("id" to response.getString("id"), "object" to "chat.completion.chunk",
                "created" to response.optLong("created", 0), "model" to response.getString("model"),
                "choices" to JSONArray().put(jsonObject("index" to 0, "delta" to delta, "finish_reason" to finish)))
            response.opt("system_fingerprint")?.let { chunk.put("system_fingerprint", it) }
            return "data: $chunk\n\n"
        }
        val content = message.opt("content")
        if (content != null && content != JSONObject.NULL && content !is String)
            throw WireFailure(422, "SSE_CONTENT_UNSUPPORTED", "此版本只支持文本与函数调用 SSE")
        val frames = mutableListOf(chunk(jsonObject("role" to "assistant", "content" to "")))
        (content as? String)?.let { frames += chunk(jsonObject("content" to it)) }
        val extras = message.copyJson().apply { remove("role"); remove("content"); remove("tool_calls") }
        if (extras.length() > 0) frames += chunk(extras)
        val tools = message.optJSONArray("tool_calls")
        tools?.objects()?.forEachIndexed { index, call -> frames += chunk(jsonObject("tool_calls" to JSONArray().put(call.copyJson().put("index", index)))) }
        frames += chunk(JSONObject(), choice.opt("finish_reason"))
        if (includeUsage) frames += "data: ${jsonObject("id" to response.getString("id"), "object" to "chat.completion.chunk",
            "created" to response.optLong("created", 0), "model" to response.getString("model"), "choices" to JSONArray(), "usage" to response.optJSONObject("usage"))}\n\n"
        frames += "data: [DONE]\n\n"
        return frames
    }
}
