package org.exomind.androbody.nativeapp

import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

/** 📌【2026-10-28】Named numeric loopback avoids host reverse-DNS aliases changing fixture URLs. */
internal fun loopbackServer() = MockWebServer().apply {
    start(InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
}

/** MockWebServer.url uses canonical hostnames even for a named numeric bind address. */
internal fun MockWebServer.loopbackUrl(path: String) = url(path).newBuilder().host("127.0.0.1").build()
