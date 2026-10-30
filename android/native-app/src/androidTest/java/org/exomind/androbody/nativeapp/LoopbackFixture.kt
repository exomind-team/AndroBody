package org.exomind.androbody.nativeapp

import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

/** Same explicit loopback boundary as host fixtures; no DNS alias grants plaintext access. */
internal fun loopbackServer() = MockWebServer().apply {
    start(InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
}

internal fun MockWebServer.loopbackUrl(path: String) = url(path).newBuilder().host("127.0.0.1").build()
