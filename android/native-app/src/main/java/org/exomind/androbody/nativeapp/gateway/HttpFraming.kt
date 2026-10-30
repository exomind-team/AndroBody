package org.exomind.androbody.nativeapp.gateway

import org.exomind.androbody.nativeapp.core.WireFailure
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** Strict local HTTP/1.x subset. Reject ambiguous framing instead of permissive request smuggling. */
data class HttpHead(val method: String, val path: String, val headers: Map<String, String>, val length: Int)
object HttpFraming {
    fun head(input: InputStream): HttpHead {
        val start = line(input, 4096).split(' ')
        if (start.size != 3 || start[2] !in setOf("HTTP/1.1", "HTTP/1.0")) throw WireFailure(400, "HTTP_FORMAT", "无效请求行")
        val target = start[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        if (!path.startsWith('/') || target.contains('#') || (target.contains('?') &&
            !(path in setOf("/v1/messages", "/v1/messages/count_tokens") && query == "beta=true")))
            throw WireFailure(400, "PATH", "只接受固定端点与 Anthropic beta=true 标记，不接收上游 URL")
        val headers = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val row = line(input, 8192)
            total += row.length + 2
            if (total > 32_768) throw WireFailure(431, "HEADERS_TOO_LARGE", "请求头过大")
            if (row.isEmpty()) break
            val colon = row.indexOf(':')
            if (colon <= 0 || row.first().isWhitespace()) throw WireFailure(400, "HEADER_FORMAT", "不接受折叠请求头")
            val name = row.substring(0, colon).lowercase()
            if (!name.matches(Regex("[a-z0-9-]+")) || headers.containsKey(name)) throw WireFailure(400, "DUPLICATE_HEADER", "重复或无效请求头")
            headers[name] = row.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding") || headers.containsKey("content-encoding"))
            throw WireFailure(415, "BODY_ENCODING_UNSUPPORTED", "当前本机端点要求 Content-Length 和未压缩 JSON")
        if (headers.containsKey("origin")) throw WireFailure(403, "BROWSER_ORIGIN_REFUSED", "本机网关不接受浏览器跨源访问")
        val length = if (start[0] == "POST") headers["content-length"]?.toIntOrNull()
            ?: throw WireFailure(411, "LENGTH_REQUIRED", "POST 需要 Content-Length") else 0
        if (length !in 0..MAX_BODY) throw WireFailure(413, "BODY_TOO_LARGE", "请求体超过 2 MiB")
        if (start[0] == "POST" && headers["content-type"]?.substringBefore(';')?.trim() != "application/json")
            throw WireFailure(415, "JSON_REQUIRED", "需要 application/json")
        if (headers["expect"] != null && headers["expect"]?.lowercase() != "100-continue")
            throw WireFailure(417, "EXPECT_UNSUPPORTED", "不支持此 Expect")
        return HttpHead(start[0], path, headers, length)
    }
    fun body(input: InputStream, size: Int): String {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(bytes, offset, size - offset)
            if (n < 0) throw WireFailure(400, "TRUNCATED_BODY", "请求体未完成")
            offset += n
        }
        return bytes.toString(Charsets.UTF_8)
    }
    private fun line(input: InputStream, max: Int): String {
        val bytes = ByteArrayOutputStream()
        var previous = -1
        while (bytes.size() <= max) {
            val next = input.read()
            if (next < 0) throw WireFailure(400, "TRUNCATED_HEADER", "请求头未完成")
            if (previous == 13 && next == 10) return bytes.toByteArray().dropLast(1).toByteArray().toString(Charsets.ISO_8859_1)
            if (next == 10 || (previous == 13 && next != 10)) throw WireFailure(400, "LINE_ENDING", "HTTP 行必须以 CRLF 结束")
            bytes.write(next); previous = next
        }
        throw WireFailure(431, "LINE_TOO_LONG", "请求头行过长")
    }
    const val MAX_BODY = 2 * 1024 * 1024
}

class HttpWriter(private val output: OutputStream) {
    var started = false; private set
    var streaming = false; private set
    @Synchronized fun interim() { output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); output.flush() }
    @Synchronized fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        check(!started); started = true
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write(("HTTP/1.1 $status ${reason(status)}\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nX-Should-Retry: false\r\nConnection: close\r\n" +
            headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray())
        output.write(bytes); output.flush()
    }
    @Synchronized fun startSse(requestId: String) {
        check(!started); started = true; streaming = true
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\nX-AndroMorph-Stream-Mode: buffered-final\r\n" +
            "X-AndroMorph-Request-Id: $requestId\r\n\r\n").toByteArray()); output.flush()
    }
    @Synchronized fun frame(value: String) { check(streaming); output.write(value.toByteArray(Charsets.UTF_8)); output.flush() }
    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Content Too Large"; 415 -> "Unsupported Media Type"
        422 -> "Unprocessable Content"; 429 -> "Too Many Requests"; 501 -> "Not Implemented"; 503 -> "Service Unavailable"; else -> "Gateway Error"
    }
}
