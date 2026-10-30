package org.exomind.androbody.nativeapp

import org.exomind.androbody.nativeapp.core.WireFailure
import org.exomind.androbody.nativeapp.gateway.*
import org.exomind.androbody.nativeapp.model.AnthropicWire
import org.exomind.androbody.nativeapp.model.OpenAiWire
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpFramingTest {
    private fun parse(value: String) = HttpFraming.head(ByteArrayInputStream(value.toByteArray()))
    @Test fun acceptsDocumentedClaudeBetaPath() {
        val head = parse("POST /v1/messages?beta=true HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}")
        assertEquals("/v1/messages", head.path); assertEquals(2, head.length)
    }
    @Test fun duplicateLengthsAreRejected() {
        assertThrows(WireFailure::class.java) { parse("POST /v1/messages HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n") }
    }
    @Test fun chunkedBodiesAreExplicitlyUnsupported() {
        assertThrows(WireFailure::class.java) { parse("POST /v1/messages HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n") }
    }
    @Test fun arbitraryUpstreamQueryIsRejected() {
        assertThrows(WireFailure::class.java) { parse("GET /v1/messages?url=https://elsewhere HTTP/1.1\r\n\r\n") }
    }
    @Test fun browserOriginIsRefused() {
        assertThrows(WireFailure::class.java) { parse("GET /health HTTP/1.1\r\nOrigin: https://example.org\r\n\r\n") }
    }
    @Test fun incompleteBodiesDoNotBecomeValidJson() {
        assertThrows(WireFailure::class.java) { HttpFraming.body(ByteArrayInputStream("{".toByteArray()), 2) }
    }
    @Test fun oversizedBodyIsRejectedBeforeAllocation() {
        assertThrows(WireFailure::class.java) { parse("POST /v1/messages HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 2147483647\r\n\r\n") }
    }
    @Test fun localRouteErrorHasStableSourceCodeAndRequestId() {
        val payload = gatewayErrorPayload(OpenAiWire, WireFailure(404, "ENDPOINT_NOT_FOUND", "此端点未实现"), "req-local")
        val error = payload.getJSONObject("error")
        assertEquals("local-gateway", error.getString("source"))
        assertEquals("ENDPOINT_NOT_FOUND", error.getString("code"))
        assertEquals("req-local", error.getString("request_id"))
    }
    @Test fun upstreamHttpErrorCannotBeMistakenForLocalRoute404() {
        val payload = gatewayErrorPayload(AnthropicWire, WireFailure(404, "UPSTREAM_HTTP_404", "上游返回 HTTP 404"), "req-upstream")
        val error = payload.getJSONObject("error")
        assertEquals("upstream", error.getString("source"))
        assertEquals("UPSTREAM_HTTP_404", error.getString("code"))
        assertEquals("api_error", error.getString("type"))
        assertEquals("req-upstream", payload.getString("request_id"))
    }
}
