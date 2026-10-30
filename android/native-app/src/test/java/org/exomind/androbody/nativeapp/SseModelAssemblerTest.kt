package org.exomind.androbody.nativeapp.model

import org.exomind.androbody.nativeapp.config.ModelProtocol
import org.exomind.androbody.nativeapp.core.WireFailure
import org.junit.Assert.*
import org.junit.Test

class SseModelAssemblerTest {
    private fun event(stream: SseModelAssembler, json: String) {
        stream.acceptLine("data: " + json)
        stream.acceptLine("")
    }

    @Test fun openAiTextDeltasProduceOneCompleteAssistantMessage() {
        val rendered = StringBuilder()
        val stream = SseModelAssembler(ModelProtocol.OPENAI) { rendered.append(it) }
        event(stream, """{"choices":[{"delta":{"role":"assistant","content":"**Hello"}}]}""")
        event(stream, """{"choices":[{"delta":{"content":" world**"},"finish_reason":null}]}""")
        event(stream, """{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        event(stream, "[DONE]")

        val response = stream.finish()
        assertEquals("**Hello world**", OpenAiWire.text(response))
        assertEquals("**Hello world**", rendered.toString())
        assertTrue(OpenAiWire.finished(response))
    }

    @Test fun openAiToolArgumentsStayHiddenUntilCompleteAndValidated() {
        val rendered = StringBuilder()
        val stream = SseModelAssembler(ModelProtocol.OPENAI) { rendered.append(it) }
        event(stream, """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"am_test","arguments":"{\"x\":"}}]},"finish_reason":null}]}""")
        event(stream, """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]},"finish_reason":"tool_calls"}]}""")
        event(stream, "[DONE]")

        val response = stream.finish()
        val call = OpenAiWire.calls(response).single()
        assertEquals("call_1", call.id)
        assertEquals("am_test", call.name)
        assertEquals(1, call.arguments.getInt("x"))
        assertEquals("", rendered.toString())
    }

    @Test fun openAiToolIndexBeyondTheOldBatchCapIsAccepted() {
        val stream = SseModelAssembler(ModelProtocol.OPENAI) {}
        event(stream, """{"choices":[{"delta":{"tool_calls":[{"index":40,"id":"call_41","type":"function","function":{"name":"am_test","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""")
        event(stream, "[DONE]")
        assertEquals("call_41", OpenAiWire.calls(stream.finish()).single().id)
    }

    @Test fun anthropicTextAndToolUseKeepDifferentPresentationAndExecutionChannels() {
        val rendered = StringBuilder()
        val stream = SseModelAssembler(ModelProtocol.ANTHROPIC) { rendered.append(it) }
        event(stream, """{"type":"message_start","message":{"id":"msg_1","role":"assistant","model":"fixture","content":[],"usage":{"input_tokens":3}}}""")
        event(stream, """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        event(stream, """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Checking"}}""")
        event(stream, """{"type":"content_block_stop","index":0}""")
        event(stream, """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool_1","name":"am_test","input":{}}}""")
        event(stream, """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"x\":"}}""")
        event(stream, """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"1}"}}""")
        event(stream, """{"type":"content_block_stop","index":1}""")
        event(stream, """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":4}}""")
        event(stream, """{"type":"message_stop"}""")

        val response = stream.finish()
        assertEquals("Checking", rendered.toString())
        assertEquals("Checking", AnthropicWire.text(response))
        assertEquals(4, response.getJSONObject("usage").getInt("output_tokens"))
        assertEquals(1, AnthropicWire.calls(response).single().arguments.getInt("x"))
        assertFalse(AnthropicWire.finished(response))
    }

    @Test fun incompleteToolBlockNeverBuildsAnExecutableMessage() {
        val stream = SseModelAssembler(ModelProtocol.ANTHROPIC) {}
        event(stream, """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool_1","name":"am_test","input":{}}}""")
        event(stream, """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"x\":1}"}}""")
        event(stream, """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
        event(stream, """{"type":"message_stop"}""")

        assertEquals("INCOMPLETE_STREAM", assertThrows(WireFailure::class.java) { stream.finish() }.code)
    }
}
