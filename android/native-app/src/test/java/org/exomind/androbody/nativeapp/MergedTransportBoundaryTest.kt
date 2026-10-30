package org.exomind.androbody.nativeapp

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.core.WireFailure
import org.exomind.androbody.nativeapp.diagnostics.*
import org.exomind.androbody.nativeapp.gateway.gatewayErrorPayload
import org.exomind.androbody.nativeapp.model.*
import org.exomind.androbody.nativeapp.model.endpoint.EndpointMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Counterexamples introduced by combining local diagnostics with the handoff transport. */
class MergedTransportBoundaryTest {
    @Test fun blankKeyCannotEscapeItsSavedDestinationForSaveOrProbe() {
        val saved = ModelSettings(ModelProtocol.ANTHROPIC, "https://one.invalid/anthropic", "saved-key", "fixture")
        fun submission(url: String = saved.baseUrl, protocol: ModelProtocol = saved.protocol,
            mode: EndpointMode = EndpointMode.AUTO, key: String = "") = EndpointPolicy.submission(
                protocol, url, key, "fixture", 128, false, mode, saved)
        assertEquals("saved-key", submission().apiKey)
        for (address in listOf("https://two.invalid/anthropic", "https://one.invalid/v1/messages"))
            assertThrows(IllegalArgumentException::class.java) { submission(url = address) }
        assertThrows(IllegalArgumentException::class.java) { submission(protocol = ModelProtocol.OPENAI) }
        assertThrows(IllegalArgumentException::class.java) { submission(mode = EndpointMode.EXACT) }
        assertEquals("fresh-key", submission(url = "https://two.invalid/anthropic", key = "fresh-key").apiKey)
    }

    @Test fun classifiedTransportErrorsRetainUpstreamAttribution() {
        for (code in listOf("UPSTREAM_HTTP_404", "UPSTREAM_ROUTE_NOT_FOUND", "UPSTREAM_AUTHENTICATION", "UPSTREAM_NETWORK")) {
            val error = gatewayErrorPayload(AnthropicWire, WireFailure(502, code, "fixture"), "parent")
                .getJSONObject("error")
            assertEquals("upstream", error.getString("source"))
            assertEquals("parent", error.getString("request_id"))
            assertEquals(code, error.getString("code"))
        }
    }

    @Test fun metadataHidesNumericContentAndExplicitCaptureRedactsBrokenJsonSecrets() {
        val raw = """{"content":{"pin":864209,"model":"private-content"},"usage":{"input_tokens":3},"unknown_number":938271}"""
        val view = JsonTraceView.structure(raw.toByteArray(), emptyList())
        assertFalse(view.contains("864209")); assertFalse(view.contains("private-content")); assertFalse(view.contains("938271"))
        assertEquals(3, JSONObject(view).getJSONObject("usage").getInt("input_tokens"))
        val partial = """{"apiKey":"unlisted-credential","access_token":"another-credential","text":"truncated"""
        val captured = JsonTraceView.capture(partial.toByteArray(), emptyList())
        assertFalse(captured.contains("unlisted-credential")); assertFalse(captured.contains("another-credential"))
    }

    @Test fun serverReceivedThenTimeoutRemainsUnknownAndDoesNotReplay() = runBlocking {
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(2, TimeUnit.SECONDS))
            val events = CopyOnWriteArrayList<TraceEvent>()
            val settings = ModelSettings(ModelProtocol.ANTHROPIC, server.loopbackUrl("/anthropic").toString(),
                "fixture-key", "fixture", allowInsecureLocal = true)
            val failure = try {
                ModelHttpClient(traces = TraceSink { events += it }, requestBudgetMs = 500)
                    .request(settings, JSONObject().put("model", "fixture"))
                throw AssertionError("Expected timeout")
            } catch (error: WireFailure) { error }
            assertTrue(failure.code in setOf("UPSTREAM_TIMEOUT", "UPSTREAM_REQUEST_BUDGET"))
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
            assertFalse(events.any { it.stage == "request_completed" })
            assertTrue(summarizeTrace(events).title.contains("未知"))
        }
    }

    @Test fun missingSendEvidenceIsOnlyAPlanInBothUiAndExportProjection() {
        val event = TraceEvent("trace", "task", "diagnostic", 4, 1, 1, 1, "attempt_start",
            mapOf("url" to "https://fixture.invalid/v1/messages"))
        val summary = summarizeTrace(listOf(event))
        assertNull(summary.actualUrl)
        assertTrue(summary.missingStart)
        assertTrue(summary.readable().contains("缺少发送证据"))
        assertTrue(summary.json().isNull("actualUrl"))
        assertEquals(summary.readable(), summary.json().getString("summary"))
    }
}
