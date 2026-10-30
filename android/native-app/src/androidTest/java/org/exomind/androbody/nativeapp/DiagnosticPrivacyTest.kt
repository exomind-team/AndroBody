package org.exomind.androbody.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import org.exomind.androbody.nativeapp.diagnostics.DiagnosticStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Actual Android Keystore, one-request grant, origin fencing, expiry and late-writer checks. */
class DiagnosticPrivacyTest {
    @Test fun explicitContentGrantIsEncryptedScopedBoundedAndRevocable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".verification"))
        var now = System.currentTimeMillis()
        val store = DiagnosticStore(context, nowMillis = { now })
        store.armContentCapture()
        assertFalse(store.beginCapture("gateway", "gateway"))
        assertFalse(store.beginCapture("event", "event"))
        assertTrue(store.beginCapture("native-one", "native"))
        assertFalse(store.beginCapture("native-two", "native"))
        val body = """{"text":"private-capture-sentinel","apiKey":"unknown-fixture-credential"}""".toByteArray()
        repeat(12) { store.recordCapture("native-one", it, "request_body_prefix", body, emptyList()) }
        val metadata = store.export(false).toString()
        assertFalse(metadata.contains("private-capture-sentinel"))
        val content = store.export(true).getJSONObject("explicitContentCapture")
        assertEquals(8, content.getJSONArray("parts").length())
        assertTrue(content.toString().contains("private-capture-sentinel"))
        assertFalse(content.toString().contains("unknown-fixture-credential"))
        val disk = File(context.filesDir, "native-v1/network-diagnostic-capture.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(disk.contains("private-capture-sentinel"))
        assertFalse(disk.contains("unknown-fixture-credential"))
        store.clearContentCapture()
        store.recordCapture("native-one", 20, "response_body", body, emptyList())
        assertFalse(store.export(true).toString().contains("private-capture-sentinel"))
        store.armContentCapture(); assertTrue(store.beginCapture("probe", "diagnostic"))
        store.recordCapture("probe", 1, "response_body", body, emptyList())
        now += TimeUnit.MINUTES.toMillis(11)
        val expired = store.export(true)
        assertFalse(expired.has("explicitContentCapture"))
        assertEquals("expired_or_absent", expired.getString("captureStatus"))
        val out = File(context.filesDir, "qa/evidence").apply { mkdirs() }
        File(out, "capture-boundaries.json").writeText("""{"status":"PASS","scope":"Android Keystore/origin/one-request/8-parts/clear/late-writer/expiry","realPrivateData":"NOT_USED"}""")
    }
}
