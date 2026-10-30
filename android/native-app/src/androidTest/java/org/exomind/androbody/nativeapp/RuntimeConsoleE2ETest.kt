package org.exomind.androbody.nativeapp

import android.content.ClipboardManager
import android.provider.DocumentsContract
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.exomind.androbody.nativeapp.config.NativeCapabilityChoice
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.diagnostics.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

/** Exercises the app-owned trace, Agent metadata query and real SAF export on the verification package. */
class RuntimeConsoleE2ETest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".verification"))
    }

    @Test fun fixturePlatformDenialIsVisibleToAgentAndRuntimeConsole() {
        val context = context()
        val graph = context.graph
        val requestId = "runtime-block-${UUID.randomUUID()}"
        val tool = object : BodyTool {
            override val capability = Capability("am_fixture_blocked", "fixture", "fixture", objectSchema(), Effect.CONTROL, false)
            override suspend fun execute(invocation: Invocation): ToolResult =
                platformCall("test-fixture.PermissionCheck") { throw SecurityException("fixture denial") }
        }
        val broker = CapabilityBroker(ToolRegistry(listOf(tool)), object : PermissionPolicy {
            override fun denial(principal: Principal, capability: Capability): String? = null
        }, ApprovalPort { _, _ -> true }, memoryJournal(), runtimeLog = graph.runtimeLog)
        val blocked = runBlocking {
            broker.invoke(Invocation(requestId, "fixture-call", Principal("native:fixture", Origin.NATIVE),
                "am_fixture_blocked", JSONObject()))
        }
        assertEquals(Outcome.DENIED, blocked.outcome)

        runBlocking {
            graph.preferences.update { current -> current.copy(nativeCapabilityChoices =
                current.nativeCapabilityChoices + ("am_runtime_trace_read" to NativeCapabilityChoice.ALLOW)) }
        }
        assertEquals(NativeCapabilityChoice.ALLOW, graph.preferences.state.value.nativeCapabilityChoices["am_runtime_trace_read"])
        val read = runBlocking {
            graph.broker.invoke(Invocation("self-diagnosis", "trace-read", Principal("native:user", Origin.NATIVE),
                "am_runtime_trace_read", JSONObject().put("requestId", requestId).put("limit", 50)))
        }
        val agentView = read.data.toString()
        assertEquals("Runtime trace read failed: ${read.toJson()}", Outcome.COMPLETED, read.outcome)
        assertTrue(agentView.contains("android_call_blocked"))
        assertTrue(agentView.contains(CapabilityFeasibility.BLOCKED_OBSERVED.name))
        assertTrue(agentView.contains("test-fixture.PermissionCheck"))
        assertFalse(agentView.contains("fixture denial"))

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("nav-RECORDS").performClick()
            compose.onNodeWithTag("records-tab-runtime").performClick()
            compose.onNodeWithTag("runtime-console-search").performTextInput(requestId)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("实测受阻", substring = false).fetchSemanticsNodes().isNotEmpty()
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.pressBack()
            compose.waitForIdle(); device.waitForIdle()
            val out = File(context.filesDir, "qa/evidence").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(out, "runtime-console-blocked-fixture.png")))
            compose.onNodeWithTag("runtime-console-copy").performClick()
            val copied = context.getSystemService(ClipboardManager::class.java).primaryClip
                ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            assertTrue(copied.contains(requestId))
            assertTrue(copied.contains("BLOCKED_OBSERVED"))
        }
    }

    @Test fun sensorToolReceiptMatchesPersistedWorkspaceArtifact() = runBlocking {
        val context = context()
        val requestId = "sensor-artifact-${UUID.randomUUID()}"
        val receipt = context.graph.broker.invoke(Invocation(requestId, "sensor-${UUID.randomUUID()}",
            Principal("native:user", Origin.NATIVE), "am_sensor_snapshot", JSONObject().put("seconds", 1)))
        assertEquals("The API35 verification AVD includes its accelerometer fixture", Outcome.COMPLETED, receipt.outcome)
        val artifact = receipt.data.getJSONObject("artifact")
        val readback = context.graph.workspace.read(artifact.getString("path"))
        assertEquals(artifact.getString("path"), readback.getString("path"))
        assertEquals(artifact.getString("sha256"), readback.getString("sha256"))
        assertEquals(artifact.getInt("bytes"), readback.getInt("bytes"))
        assertTrue(JSONObject(readback.getString("text")).getJSONArray("samples").length() > 0)
    }

    @Test fun filteredRuntimeConsoleExportUsesDocumentsUiAndReadsBackExactBytes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = context()
        val requestId = "runtime-export-${UUID.randomUUID()}"
        context.graph.runtimeLog.record("fixture", "runtime_console_export_fixture", requestId, "test",
            RuntimeLogLevel.WARN, EvidenceKind.STATIC_CANDIDATE, CapabilityFeasibility.BLOCKER_CANDIDATE,
            mapOf("capability" to "am_fixture_intent", "candidate" to "static-only",
                "prompt" to "private-console-export-sentinel"))

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("nav-RECORDS").performClick()
            compose.onNodeWithText("运行台", substring = false).performClick()
            compose.onNodeWithTag("runtime-console-search").performTextInput(requestId)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("runtime_console_export_fixture", substring = false).fetchSemanticsNodes().isNotEmpty() }
            val before = context.graph.networkDiagnostics.lastExport.value?.atMillis ?: 0L
            compose.onNodeWithTag("runtime-console-export").performClick()
            val device = UiDevice.getInstance(instrumentation)
            val save = device.wait(Until.findObject(By.text(Pattern.compile("(?i)save|保存"))), 10_000)
            assertNotNull("Real DocumentsUI Save action unavailable; retain the failure instead of faking export", save)
            save!!.click()
            compose.waitUntil(15_000) { (context.graph.networkDiagnostics.lastExport.value?.atMillis ?: 0L) > before }
            val exported = context.graph.networkDiagnostics.lastExport.value!!
            assertEquals("VERIFIED", exported.status)
            val uri = Uri.parse(exported.uri)
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertEquals(exported.sha256, sha256(bytes))
            val report = JSONObject(String(bytes, Charsets.UTF_8))
            assertEquals("andromorph.runtime-console.v1", report.getString("schema"))
            assertTrue(report.getJSONArray("events").toString().contains(requestId))
            assertTrue(report.getJSONArray("events").toString().contains("BLOCKER_CANDIDATE"))
            assertFalse(report.toString().contains("private-console-export-sentinel"))
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 5_000))
            compose.waitForIdle(); device.waitForIdle()
            val out = java.io.File(context.filesDir, "qa/evidence").apply { mkdirs() }
            java.io.File(out, "runtime-console-export-result.json").writeText(
                JSONObject().put("status", "PASS").put("bytes", bytes.size).put("sha256", exported.sha256)
                    .put("scope", "filtered runtime trace exported by DocumentsUI and independently read back").toString(2))
            // Delete only the unique test-owned export created by this test; no existing user document is touched.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
    }

    @Test fun legacyNetworkJournalLoadsIntoRuntimeStoreAsTransportEvidence() {
        val context = context()
        val legacyDirectory = File(context.filesDir, "native-v1/network-diagnostics").apply { mkdirs() }
        val legacyFile = File(legacyDirectory, "trace-0.jsonl")
        assertFalse("Verification package should not contain a preexisting legacy journal", legacyFile.exists())
        val oldEvent = JSONObject().put("traceId", "legacy-network-trace").put("requestId", "legacy-request")
            .put("origin", "native").put("sequence", 1).put("atMillis", System.currentTimeMillis())
            .put("elapsedMicros", 10).put("attempt", 1).put("stage", "request_failed")
            .put("fields", JSONObject().put("kind", "TIMEOUT"))
        legacyFile.writeText(oldEvent.toString() + "\n", Charsets.UTF_8)
        val runtimeDirectory = File(context.filesDir, "native-v1/runtime-console").apply { mkdirs() }
        val retainedFile = File(runtimeDirectory, "trace-5.jsonl")
        assertFalse("Verification package should not contain a preexisting rotated runtime journal", retainedFile.exists())
        val retainedRequestId = "retained-runtime-request"
        val retainedEvent = TraceEvent("retained-runtime-trace", retainedRequestId, "native", 1,
            System.currentTimeMillis() - 86_400_000L, 10, 1, "retained_runtime_console_event",
            mapOf("capability" to "am_fixture_intent"), RuntimeLogLevel.WARN.name, "capability",
            EvidenceKind.ACTUAL_ATTEMPT.name, CapabilityFeasibility.SUBMITTED.name, "prior-process-fixture")
        retainedFile.writeText(DiagnosticStore.encode(retainedEvent).toString() + "\n", Charsets.UTF_8)
        try {
            val store = DiagnosticStore(context)
            val loaded = store.events.value.single { it.traceId == "legacy-network-trace" }
            assertEquals("transport", loaded.subsystem)
            assertEquals(RuntimeLogLevel.ERROR.name, loaded.level)
            assertEquals(EvidenceKind.RESULT.name, loaded.evidenceKind)
            assertTrue(store.retainedForConsole().any { it.traceId == "retained-runtime-trace" })
            val exported = store.export()
            assertEquals("andromorph.network-diagnostics.v2", exported.getString("schema"))
            assertTrue(exported.getJSONArray("events").toString().contains("legacy-network-trace"))
            // Refresh the already-running process's live store with the same prior-process event
            // so the UI projection is tested without killing the instrumentation host.
            context.graph.networkDiagnostics.record(retainedEvent)

            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("nav-RECORDS").performClick()
                compose.onNodeWithTag("records-tab-runtime").performClick()
                compose.onNodeWithTag("runtime-console-search").performTextInput(retainedRequestId)
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("retained_runtime_console_event", substring = false)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("前次进程", substring = false).assertExists()
            }
        } finally {
            runCatching { legacyFile.delete() }
            runCatching { retainedFile.delete() }
        }
    }

    private fun memoryJournal() = object : ReceiptJournal {
        override fun begin(invocation: Invocation) = Unit
        override fun finish(invocation: Invocation, receipt: Receipt) = Unit
    }

    @Test fun assistantConversationRendersGfmTableWithoutProviderTraffic() {
        val context = context()
        val graph = context.graph
        runBlocking { graph.native.state.first { !it.initializing } }
        val store = graph.nativeSessions
        val session = store.create("Markdown 表格回归")
        val now = System.currentTimeMillis()
        store.save(session.info.id, JSONObject().put("schema", 3).put("running", false)
            .put("mayHaveDeviceEffects", false).put("history", JSONArray())
            .put("contextBudget", JSONObject().put("requestBytes", 1024)
                .put("tokenEstimateMin", 64).put("tokenEstimateMax", 128)
                .put("providerInputTokens", 41).put("providerInputField", "prompt_tokens")
                .put("providerModel", "fixture-model").put("providerProtocol", "OPENAI"))
            .put("entries", JSONArray()
                .put(JSONObject().put("id", "markdown-user").put("role", "user").put("text", "请显示表格")
                    .put("at", now).put("requestId", "markdown-fixture"))
                .put(JSONObject().put("id", "markdown-assistant").put("role", "assistant")
                    .put("text", "| Tool | State |\n| --- | --- |\n| Notifications | Permission required |\n| Workspace | Editable |")
                    .put("at", now + 1).put("requestId", "markdown-fixture"))))
        graph.native.switchSession(session.info.id)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Notifications", substring = false).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Tool", substring = false).assertExists()
            compose.onNodeWithText("State", substring = false).assertExists()
            compose.onNodeWithText("Permission required", substring = false).assertExists()
            compose.onNodeWithText("Editable", substring = false).assertExists()
            compose.onNodeWithTag("context-budget-open").performClick()
            compose.onNodeWithTag("context-budget-dialog").assertExists()
            compose.onNodeWithText("未知 · 尚无可核验的窗口元数据", substring = false).assertExists()
            compose.onNodeWithText("1 KiB / 2 MiB", substring = false).assertExists()
            compose.onNodeWithText("64–128 · 低置信估算", substring = false).assertExists()
            compose.onNodeWithText("41 input tokens · prompt_tokens", substring = false).assertExists()
            compose.onNodeWithText("OPENAI · fixture-model", substring = false).assertExists()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val out = File(context.filesDir, "qa/evidence").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(out, "context-budget-inspector.png")))
            compose.onNodeWithText("关闭", substring = false).performClick()
        }
        assertFalse(context.graph.networkDiagnostics.events.value.any { it.origin == "native" && it.subsystem == "transport" })
    }
}
