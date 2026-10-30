package org.exomind.androbody.nativeapp

import org.exomind.androbody.nativeapp.config.ModelProtocol
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.gateway.SseEncoder
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SseEncoderTest {
    @Test fun anthropicFinalEnvelopeHasOrderedEndsAndOriginalSignature() {
        val response = jsonObject("id" to "m", "type" to "message", "role" to "assistant", "model" to "fixture", "stop_reason" to "end_turn", "stop_sequence" to null,
            "usage" to jsonObject("input_tokens" to 20, "output_tokens" to 8), "content" to JSONArray()
                .put(jsonObject("type" to "thinking", "thinking" to "test", "signature" to "opaque-sig"))
                .put(jsonObject("type" to "text", "text" to "真实文本")))
        val frames = SseEncoder.frames(ModelProtocol.ANTHROPIC, response)
        assertTrue(frames.first().startsWith("event: message_start"))
        assertTrue(frames[frames.lastIndex - 1].startsWith("event: message_delta"))
        assertTrue(frames.last().startsWith("event: message_stop"))
        assertTrue(frames.joinToString("").contains("opaque-sig"))
        assertEquals(2, frames.count { it.startsWith("event: content_block_stop") })
    }
    @Test fun unknownBlocksAreNotSilentlyDropped() {
        val response = jsonObject("content" to JSONArray().put(jsonObject("type" to "unknown_future_block")))
        assertThrows(WireFailure::class.java) { SseEncoder.frames(ModelProtocol.ANTHROPIC, response) }
    }
    @Test fun openAiKeepsHostCallIdentityAndFinalDone() {
        val response = jsonObject("id" to "c", "model" to "fixture", "created" to 1, "choices" to JSONArray().put(jsonObject("index" to 0, "finish_reason" to "tool_calls",
            "message" to jsonObject("role" to "assistant", "content" to null, "tool_calls" to JSONArray().put(jsonObject("id" to "host-call", "type" to "function",
                "function" to jsonObject("name" to "Read", "arguments" to "{}")))))), "usage" to jsonObject("total_tokens" to 5))
        val frames = SseEncoder.frames(ModelProtocol.OPENAI, response, true)
        assertEquals("data: [DONE]\n\n", frames.last())
        assertTrue(frames.joinToString("").contains("host-call"))
        assertTrue(frames[frames.lastIndex - 1].contains("\"choices\":[]"))
    }
}
