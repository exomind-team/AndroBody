package org.exomind.androbody.nativeapp.core

import java.io.InputStream
import java.io.ByteArrayOutputStream

fun InputStream.readAtMost(limit: Int): ByteArray {
    require(limit >= 0)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var remaining = limit
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count); remaining -= count
    }
    return output.toByteArray()
}
