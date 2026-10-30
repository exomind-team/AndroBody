package org.exomind.androbody.nativeapp

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.diagnostics.RequestContext
import org.exomind.androbody.nativeapp.diagnostics.sha256
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/** Uses actual system CreateDocument. Not an in-memory or direct-file substitute for SAF. */
class NetworkDiagnosticsSafTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun exportThroughSystemPickerAndReadBackExactDiagnostic() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".verification"))
        loopbackServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","application/json")
                .setBody("""{"type":"message","role":"assistant","content":[{"type":"text","text":"fixture"}],"stop_reason":"end_turn"}"""))
            runBlocking {
                val settings=ModelSettings(ModelProtocol.ANTHROPIC,server.loopbackUrl("/v1").toString(),"fixture-saf-key","fixture",allowInsecureLocal=true)
                val body=JSONObject().put("model","fixture").put("max_tokens",64).put("messages",JSONArray().put(JSONObject().put("role","user").put("content","fixture-saf-private-prompt")))
                context.graph.http.request(settings,body,context=RequestContext(origin="diagnostic"))
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("nav-RECORDS").performClick()
                compose.onNodeWithText("网络", substring = false).performClick()
                compose.onNodeWithTag("diagnostics-export").performClick()
                val device=UiDevice.getInstance(instrumentation)
                val save=device.wait(Until.findObject(By.text(Pattern.compile("(?i)save|保存"))),10_000)
                assertNotNull("Real DocumentsUI Save action unavailable: retain failure, do not fake export",save)
                save!!.click() // once only; never repeat Save after a stale element or timeout
                compose.waitUntil(15_000){context.graph.networkDiagnostics.lastExport.value!=null}
                val exported=context.graph.networkDiagnostics.lastExport.value!!
                assertEquals("VERIFIED",exported.status)
                val bytes=context.contentResolver.openInputStream(Uri.parse(exported.uri))!!.use{it.readBytes()}
                assertEquals(exported.sha256,sha256(bytes))
                val text=String(bytes,Charsets.UTF_8)
                assertFalse(text.contains("fixture-saf-key"));assertFalse(text.contains("fixture-saf-private-prompt"))
                assertEquals("andromorph.network-diagnostics.v2",JSONObject(text).getString("schema"))
                val out=File(context.filesDir,"qa/evidence").apply{mkdirs()}
                File(out,"saf-export-result.json").writeText(JSONObject().put("status","PASS").put("bytes",bytes.size)
                    .put("sha256",exported.sha256).put("scope","actual CreateDocument and independent ContentResolver readback").toString(2))
                assertTrue("DocumentsUI must return to the actual app before capturing its screen",
                    device.wait(Until.hasObject(By.pkg(context.packageName)), 5_000))
                compose.waitForIdle(); device.waitForIdle()
                device.takeScreenshot(File(out,"network-diagnostics.png"))
                // Keep only this test-owned newly-created export for manual inspection; no user file cleanup.
            }
        }
    }
}
