package org.exomind.androbody.nativeapp.model

import okhttp3.*
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.exomind.androbody.nativeapp.diagnostics.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest

/** Observe the transport that sends the request, not a UI reconstruction of settings.baseUrl. */
internal class HttpTraceObserver(private val trace: RequestTrace, private val attempt: Int,
    private val secrets: List<String>) : EventListener() {
    private fun event(name: String, vararg fields: Pair<String, String>) =
        trace.event(name, fields.associate { it.first to DiagnosticRedaction.text(it.second, secrets, 2048) }, attempt)
    override fun callStart(call: Call) = event("call_start")
    override fun dnsStart(call: Call, domainName: String) = event("dns_start", "host" to domainName)
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
        event("dns_end", "host" to domainName, "addresses" to inetAddressList.joinToString(",") { it.hostAddress.orEmpty() })
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        event("connect_start", "peer" to inetSocketAddress.toString(), "proxyType" to proxy.type().name)
    override fun secureConnectStart(call: Call) = event("tls_start")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = event("tls_end",
        "version" to (handshake?.tlsVersion?.javaName ?: "unknown"), "cipher" to (handshake?.cipherSuite?.javaName ?: "unknown"))
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) =
        event("connect_failed", "class" to ioe.javaClass.name, "peer" to inetSocketAddress.toString())
    override fun connectionAcquired(call: Call, connection: Connection) = event("connection_acquired",
        "peer" to connection.route().socketAddress.toString(), "protocol" to connection.protocol().toString())
    override fun requestHeadersStart(call: Call) = event("request_headers_start")
    override fun requestHeadersEnd(call: Call, request: Request) = event("request_headers_end",
        "method" to request.method, "url" to DiagnosticRedaction.url(request.url.toString(), secrets),
        "headers" to DiagnosticRedaction.headers(request.headers.toMap(), secrets).toString())
    override fun requestBodyStart(call: Call) = event("request_body_start")
    override fun requestBodyEnd(call: Call, byteCount: Long) = event("request_body_end", "bytes" to byteCount.toString())
    override fun responseHeadersStart(call: Call) = event("response_headers_start")
    override fun responseHeadersEnd(call: Call, response: Response) = event("response_headers_end",
        "httpStatus" to response.code.toString(), "url" to DiagnosticRedaction.url(response.request.url.toString(), secrets),
        "headers" to DiagnosticRedaction.headers(response.headers.toMap(), secrets).toString())
    override fun responseBodyEnd(call: Call, byteCount: Long) = event("response_body_end", "bytes" to byteCount.toString())
    override fun callEnd(call: Call) = event("call_end")
    override fun callFailed(call: Call, ioe: IOException) = event("call_failed", "class" to ioe.javaClass.name,
        "causeClasses" to generateSequence<Throwable>(ioe) { it.cause }.take(8).joinToString(" -> ") { it.javaClass.name })
}

/** Hash the exact bytes written at OkHttp's request-body boundary. This is not a TCP acknowledgement. */
internal class ObservedRequestBody(private val delegate: RequestBody,
    private val recorded: (ByteArray, Long, String, Boolean) -> Unit) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun isOneShot() = true // no silent replay of a generation request
    override fun writeTo(sink: BufferedSink) {
        val digest = MessageDigest.getInstance("SHA-256")
        val capture = Buffer()
        var bytes = 0L
        var complete = false
        val observer = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                val copy = Buffer(); source.copyTo(copy, 0, byteCount)
                val data = copy.readByteArray()
                digest.update(data)
                val remaining = CAPTURE_BYTES - capture.size
                if (remaining > 0) capture.write(data, 0, minOf(data.size.toLong(), remaining).toInt())
                super.write(source, byteCount)
                bytes += byteCount
            }
        }.buffer()
        try { delegate.writeTo(observer); observer.flush(); complete = true }
        finally { recorded(capture.readByteArray(), bytes, digest.digest().joinToString("") { "%02x".format(it) }, complete) }
    }
    companion object { const val CAPTURE_BYTES = 65_536L }
}
