package org.exomind.androbody.nativeapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.diagnostics.*
import org.exomind.androbody.nativeapp.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolTurnEngineTest {
    private class Journal : ReceiptJournal {
        val receipts = mutableListOf<Receipt>()
        override fun begin(invocation: Invocation) = Unit
        override fun finish(invocation: Invocation, receipt: Receipt) { receipts += receipt }
    }
    private class Fixture {
        var executions = 0
        var denied = false
        val journal = Journal()
        val tool = object : BodyTool {
            override val capability = Capability("am_test", "测试", "fixture read", objectSchema(), Effect.READ_DEVICE, true)
            override suspend fun execute(invocation: Invocation): ToolResult {
                executions++; return ToolResult.completed(jsonObject("value" to 42), "fixture observation")
            }
        }
        val broker = CapabilityBroker(ToolRegistry(listOf(tool)), object : PermissionPolicy {
            override fun denial(principal: Principal, capability: Capability) = if (denied) "revoked" else null
        }, ApprovalPort { _, _ -> true }, journal)
        val engine = ToolTurnEngine(broker)
        val principal = Principal("fixture", Origin.GATEWAY)
        fun settings(protocol: ModelProtocol) = ModelSettings(protocol, "https://fixture.invalid/v1", "not-a-real-key", "fixture-model")
    }
    private fun anthropic(tool: String? = null): JSONObject = jsonObject("id" to "msg_fixture", "type" to "message", "role" to "assistant", "model" to "fixture-model",
        "content" to JSONArray().put(if (tool == null) jsonObject("type" to "text", "text" to "42") else
            jsonObject("type" to "tool_use", "id" to "use_1", "name" to tool, "input" to JSONObject())),
        "stop_reason" to if (tool == null) "end_turn" else "tool_use", "stop_sequence" to null,
        "usage" to jsonObject("input_tokens" to 10, "output_tokens" to 2))
    private fun openai(tool: String? = null): JSONObject = jsonObject("id" to "chat_fixture", "object" to "chat.completion", "created" to 1,
        "model" to "fixture-model", "choices" to JSONArray().put(jsonObject("index" to 0, "finish_reason" to if (tool == null) "stop" else "tool_calls",
            "message" to if (tool == null) jsonObject("role" to "assistant", "content" to "42") else jsonObject("role" to "assistant", "content" to null,
                "tool_calls" to JSONArray().put(jsonObject("id" to "call_1", "type" to "function", "function" to jsonObject("name" to tool, "arguments" to "{}")))))),
        "usage" to jsonObject("prompt_tokens" to 10, "completion_tokens" to 2, "total_tokens" to 12))
    private fun anthropicBatch(count: Int): JSONObject {
        val content = JSONArray()
        repeat(count) { index -> content.put(jsonObject("type" to "tool_use", "id" to "use_$index", "name" to "am_test", "input" to JSONObject())) }
        return jsonObject("id" to "msg_batch", "type" to "message", "role" to "assistant", "model" to "fixture-model",
            "content" to content, "stop_reason" to "tool_use", "usage" to jsonObject("input_tokens" to 10, "output_tokens" to count))
    }
    private fun openaiBatch(count: Int): JSONObject {
        val calls = JSONArray()
        repeat(count) { index -> calls.put(jsonObject("id" to "call_$index", "type" to "function",
            "function" to jsonObject("name" to "am_test", "arguments" to "{}"))) }
        return jsonObject("id" to "chat_batch", "object" to "chat.completion", "created" to 1, "model" to "fixture-model",
            "choices" to JSONArray().put(jsonObject("index" to 0, "finish_reason" to "tool_calls",
                "message" to jsonObject("role" to "assistant", "content" to null, "tool_calls" to calls))),
            "usage" to jsonObject("prompt_tokens" to 10, "completion_tokens" to count, "total_tokens" to count + 10))
    }

    @Test fun hiddenAnthropicLoopPairsReceiptAndHidesInternalCall() = runTest {
        val f = Fixture(); var turns = 0
        val request = AnthropicWire.initial("fixture-model", "test", 1024, JSONArray())
        val result = f.engine.run(request, AnthropicWire, f.principal, f.settings(ModelProtocol.ANTHROPIC), "req") { next ->
            turns++
            if (turns == 1) anthropic("am_test") else {
                val blocks = next.getJSONArray("messages").getJSONObject(2).getJSONArray("content")
                assertEquals("use_1", blocks.getJSONObject(0).getString("tool_use_id"))
                anthropic()
            }
        }
        assertEquals(2, result.rounds); assertEquals(1, f.executions)
        assertEquals(10, result.response.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(20L, result.usageTotals["input_tokens"])
        assertEquals(10L, result.reportedInputUsage?.tokens)
        assertEquals("input_tokens", result.reportedInputUsage?.field)
        assertTrue(AnthropicWire.calls(result.response).isEmpty())
    }
    @Test fun hiddenOpenAiLoopPairsToolId() = runTest {
        val f = Fixture(); var turns = 0
        val result = f.engine.run(OpenAiWire.initial("fixture-model", "test", 1024, JSONArray()), OpenAiWire, f.principal,
            f.settings(ModelProtocol.OPENAI), "req") { next ->
            if (++turns == 1) openai("am_test") else {
                assertEquals("call_1", next.getJSONArray("messages").getJSONObject(3).getString("tool_call_id")); openai()
            }
        }
        assertEquals(1, f.executions); assertEquals(12, result.response.getJSONObject("usage").getInt("total_tokens"))
        assertEquals(24L, result.usageTotals["total_tokens"])
        assertEquals(10L, result.reportedInputUsage?.tokens)
        assertEquals("prompt_tokens", result.reportedInputUsage?.field)
    }
    @Test fun anthropicToolBatchAboveLegacyCapExecutesAndPairsEveryReceipt() = runTest {
        val f = Fixture(); var turns = 0
        val result = f.engine.run(AnthropicWire.initial("fixture-model", "test", 1024, JSONArray()), AnthropicWire,
            f.principal, f.settings(ModelProtocol.ANTHROPIC), "large-anthropic-batch") { request ->
            if (++turns == 1) anthropicBatch(40) else {
                val messages = request.getJSONArray("messages")
                val results = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
                assertEquals(40, results.length())
                assertEquals("use_0", results.getJSONObject(0).getString("tool_use_id"))
                assertEquals("use_39", results.getJSONObject(39).getString("tool_use_id"))
                anthropic()
            }
        }
        assertEquals(40, f.executions)
        assertEquals(40, result.receiptIds.size)
        assertEquals(2, result.rounds)
    }
    @Test fun openAiToolBatchAboveLegacyCapExecutesAndPairsEveryReceipt() = runTest {
        val f = Fixture(); var turns = 0
        val result = f.engine.run(OpenAiWire.initial("fixture-model", "test", 1024, JSONArray()), OpenAiWire,
            f.principal, f.settings(ModelProtocol.OPENAI), "large-openai-batch") { request ->
            if (++turns == 1) openaiBatch(40) else {
                val toolMessages = request.getJSONArray("messages").objects().filter { it.optString("role") == "tool" }
                assertEquals(40, toolMessages.size)
                assertEquals("call_0", toolMessages.first().getString("tool_call_id"))
                assertEquals("call_39", toolMessages.last().getString("tool_call_id"))
                openai()
            }
        }
        assertEquals(40, f.executions)
        assertEquals(40, result.receiptIds.size)
        assertEquals(2, result.rounds)
    }
    @Test fun toolLoopCanContinuePastLegacyRoundCap() = runTest {
        val f = Fixture(); var turns = 0
        val result = f.engine.run(AnthropicWire.initial("fixture-model", "test", 1024, JSONArray()), AnthropicWire,
            f.principal, f.settings(ModelProtocol.ANTHROPIC), "long-tool-loop") {
            val turn = ++turns
            if (turn <= 25) jsonObject("id" to "msg_$turn", "type" to "message", "role" to "assistant", "model" to "fixture-model",
                "content" to JSONArray().put(jsonObject("type" to "tool_use", "id" to "use_$turn", "name" to "am_test", "input" to JSONObject())),
                "stop_reason" to "tool_use", "usage" to jsonObject("input_tokens" to 1, "output_tokens" to 1))
            else anthropic()
        }
        assertEquals(26, result.rounds)
        assertEquals(25, f.executions)
        assertEquals(25, result.receiptIds.size)
    }

    @Test fun payloadAboveFormerCharacterCutoffIsSentWhenWithinTransportBudget() = runTest {
        val f = Fixture()
        val request = AnthropicWire.initial("fixture-model", "x".repeat(260_001), 1024, JSONArray())
        var observed: PreparedRequestBudget? = null
        var transportCalled = false
        val result = f.engine.run(request, AnthropicWire, f.principal, f.settings(ModelProtocol.ANTHROPIC),
            "former-character-cutoff", onRequestBudget = { observed = it }) {
            transportCalled = true
            assertTrue(it.toString().length > 250_000)
            assertTrue(it.toString().toByteArray(Charsets.UTF_8).size < ModelHttpClient.MAX_REQUEST_BYTES)
            anthropic()
        }
        assertTrue(transportCalled)
        assertTrue(observed!!.serializedBytes > 250_000)
        assertTrue(observed!!.serializedBytes < ModelHttpClient.MAX_REQUEST_BYTES)
        assertEquals(10L, result.reportedInputUsage?.tokens)
    }

    @Test fun payloadOverTransportBudgetIsVisibleAndRejectedBeforeProviderCall() = runTest {
        val f = Fixture()
        val request = AnthropicWire.initial("fixture-model", "x".repeat(ModelHttpClient.MAX_REQUEST_BYTES + 1024), 1024, JSONArray())
        var observed: PreparedRequestBudget? = null
        var transportCalled = false
        val failure = try {
            f.engine.run(request, AnthropicWire, f.principal, f.settings(ModelProtocol.ANTHROPIC),
                "transport-byte-limit", onRequestBudget = { observed = it }) {
                transportCalled = true
                anthropic()
            }
            null
        } catch (error: WireFailure) { error }
        assertEquals("CONTEXT_BUDGET", failure?.code)
        assertTrue(failure!!.safeDetail.contains("超过 2 MiB"))
        assertTrue(failure.safeDetail.contains("未发送"))
        assertTrue(observed!!.serializedBytes > ModelHttpClient.MAX_REQUEST_BYTES)
        assertFalse(transportCalled)
    }

    @Test fun mixedOwnershipRefusesBeforeAnyAndroidExecution() = runTest {
        val f = Fixture()
        val response = anthropic("am_test").apply { getJSONArray("content").put(jsonObject("type" to "tool_use", "id" to "host_1", "name" to "Bash", "input" to jsonObject("command" to "pwd"))) }
        try {
            f.engine.run(AnthropicWire.initial("fixture-model", "test", 1024, JSONArray()), AnthropicWire, f.principal,
                f.settings(ModelProtocol.ANTHROPIC), "req") { response }
            fail("Expected mixed ownership rejection")
        } catch (failure: WireFailure) { assertEquals("MIXED_TOOL_OWNERSHIP", failure.code) }
        assertEquals(0, f.executions)
    }
    @Test fun hostToolIsReturnedUntouchedAndNotExecuted() = runTest {
        val f = Fixture(); val response = anthropic("Read")
        val result = f.engine.run(AnthropicWire.initial("fixture-model", "test", 1024, JSONArray()), AnthropicWire, f.principal,
            f.settings(ModelProtocol.ANTHROPIC), "req") { response }
        assertEquals("Read", AnthropicWire.calls(result.response).single().name); assertEquals(0, f.executions)
    }
    @Test fun toolChoiceNoneDoesNotGetOverridden() {
        val f = Fixture()
        val request = AnthropicWire.initial("fixture-model", "test", 1024, JSONArray()).put("tool_choice", jsonObject("type" to "none"))
        val result = f.engine.prepare(request, AnthropicWire, f.principal, f.settings(ModelProtocol.ANTHROPIC))
        assertFalse(result.has("tools")); assertEquals("none", result.getJSONObject("tool_choice").getString("type"))
    }
    @Test fun collisionIsRejectedRatherThanReplacingHostTool() {
        val f = Fixture()
        val request = AnthropicWire.initial("fixture-model", "test", 1024, JSONArray()).put("tools", JSONArray().put(jsonObject("name" to "am_test")))
        assertThrows(WireFailure::class.java) { f.engine.prepare(request, AnthropicWire, f.principal, f.settings(ModelProtocol.ANTHROPIC)) }
    }
    @Test fun revocationAfterToolAdvertisementIsEnforced() = runTest {
        val f = Fixture(); var turns = 0
        f.engine.run(AnthropicWire.initial("fixture-model", "test", 1024, JSONArray()), AnthropicWire, f.principal, f.settings(ModelProtocol.ANTHROPIC), "req") {
            if (++turns == 1) { f.denied = true; anthropic("am_test") } else anthropic()
        }
        assertEquals(0, f.executions); assertEquals(Outcome.DENIED, f.journal.receipts.single().outcome)
    }
    @Test fun partialToolInputDoesNotExecute() = runTest {
        val f = Fixture()
        val response = openai("am_test").apply {
            getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls")
                .getJSONObject(0).getJSONObject("function").put("arguments", "{\"x\":")
        }
        try { f.engine.run(OpenAiWire.initial("fixture-model", "test", 1024, JSONArray()), OpenAiWire, f.principal,
            f.settings(ModelProtocol.OPENAI), "req") { response }; fail("Expected incomplete JSON rejection") }
        catch (failure: WireFailure) { assertEquals("INVALID_TOOL_ARGUMENTS", failure.code) }
        assertEquals(0, f.executions)
    }
    @Test fun signedThinkingSurvivesContinuation() = runTest {
        val f = Fixture(); var turns = 0
        val signature = "opaque-fixture-signature"
        val response = anthropic("am_test").apply {
            put("content", JSONArray().put(jsonObject("type" to "thinking", "thinking" to "fixture", "signature" to signature)).put(getJSONArray("content").getJSONObject(0)))
        }
        f.engine.run(AnthropicWire.initial("fixture-model", "test", 1024, JSONArray()), AnthropicWire, f.principal,
            f.settings(ModelProtocol.ANTHROPIC), "req") { request ->
            if (++turns == 1) response else {
                assertEquals(signature, request.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature")); anthropic()
            }
        }
    }

    @Test fun openAiLengthResponseContinuesWithoutAnAppSelectedOutputCap() = runTest {
        val f = Fixture(); var turns = 0; val checkpoints = mutableListOf<JSONArray>()
        val result = f.engine.run(OpenAiWire.initial("fixture-model", "write", 0, JSONArray()), OpenAiWire,
            f.principal, f.settings(ModelProtocol.OPENAI), "long-output",
            onCheckpoint = { messages, _ -> checkpoints += messages }) { request ->
            assertFalse(request.has("max_tokens"))
            if (++turns == 1) jsonObject("choices" to JSONArray().put(jsonObject("finish_reason" to "length",
                "message" to jsonObject("role" to "assistant", "content" to "part one"))))
            else {
                val messages = request.getJSONArray("messages")
                assertEquals("assistant", messages.getJSONObject(messages.length() - 2).getString("role"))
                assertEquals("part one", messages.getJSONObject(messages.length() - 2).getString("content"))
                assertTrue(messages.getJSONObject(messages.length() - 1).getString("content").contains("Continue"))
                openai()
            }
        }
        assertEquals(2, result.rounds)
        assertEquals(1, checkpoints.size)
        assertTrue(OpenAiWire.finished(result.response))
    }

    @Test fun anthropicLengthResponseContinuesAtTheProviderSegmentLimit() = runTest {
        val f = Fixture(); var turns = 0; val checkpoints = mutableListOf<JSONArray>()
        val result = f.engine.run(AnthropicWire.initial("fixture-model", "write", 0, JSONArray()), AnthropicWire,
            f.principal, f.settings(ModelProtocol.ANTHROPIC), "long-anthropic-output",
            onCheckpoint = { messages, _ -> checkpoints += messages }) { request ->
            assertEquals(8192, request.getInt("max_tokens"))
            if (++turns == 1) jsonObject("id" to "msg_partial", "type" to "message", "role" to "assistant",
                "model" to "fixture-model", "content" to JSONArray().put(jsonObject("type" to "text", "text" to "part one")),
                "stop_reason" to "max_tokens", "usage" to jsonObject("input_tokens" to 1, "output_tokens" to 8192))
            else {
                val messages = request.getJSONArray("messages")
                assertEquals("assistant", messages.getJSONObject(messages.length() - 2).getString("role"))
                assertEquals("part one", messages.getJSONObject(messages.length() - 2).getJSONArray("content").getJSONObject(0).getString("text"))
                assertTrue(messages.getJSONObject(messages.length() - 1).getString("content").contains("Continue"))
                anthropic()
            }
        }
        assertEquals(2, result.rounds)
        assertEquals(1, checkpoints.size)
        assertTrue(AnthropicWire.finished(result.response))
    }

    @Test fun readOnlyRecoveryFilterDoesNotAdvertiseOrExecuteOtherTools() = runTest {
        val f = Fixture()
        val request = AnthropicWire.initial("fixture-model", "reconcile", 128, JSONArray())
        val prepared = f.engine.prepare(request, AnthropicWire, f.principal, f.settings(ModelProtocol.ANTHROPIC),
            allowedToolNames = setOf("am_receipts_recent", "am_runtime_trace_read"))
        assertFalse(prepared.optJSONArray("tools")?.objects()?.any { it.optString("name") == "am_test" } == true)
        assertEquals(0, f.executions)
    }

    @Test fun unadvertisedPotentialIsLoggedAsUnimplementedNotPlatformBlocked() = runTest {
        val events = mutableListOf<TraceEvent>()
        var journalBegins = 0
        val logger = RuntimeEventLogger(TraceSink { event -> events.add(event); Unit }, processRunId = "run")
        val broker = CapabilityBroker(ToolRegistry(emptyList()), object : PermissionPolicy {
            override fun denial(principal: Principal, capability: Capability): String? = null
        }, ApprovalPort { _, _ -> true }, object : ReceiptJournal {
            override fun begin(invocation: Invocation) { journalBegins++ }
            override fun finish(invocation: Invocation, receipt: Receipt) = Unit
        }, runtimeLog = logger)
        val engine = ToolTurnEngine(broker)
        var failure: WireFailure? = null
        try {
            engine.run(AnthropicWire.initial("fixture-model", "test", 1024, JSONArray()), AnthropicWire,
                Principal("native", Origin.NATIVE), ModelSettings(ModelProtocol.ANTHROPIC,
                    "https://fixture.invalid/v1", "fixture-key", "fixture-model"), "theoretical-request") {
                anthropic("am_intent_send")
            }
            fail("Expected an unadvertised capability refusal")
        } catch (expected: WireFailure) { failure = expected }
        assertEquals("UNADVERTISED_CAPABILITY", failure?.code)
        assertEquals(0, journalBegins)
        val event = events.single { it.stage == "tool_not_advertised" }
        assertEquals(CapabilityFeasibility.NOT_IMPLEMENTED.name, event.feasibility)
        assertEquals("false", event.fields["platformCallAttempted"])
        assertNotEquals(CapabilityFeasibility.BLOCKED_OBSERVED.name, event.feasibility)
    }
}
