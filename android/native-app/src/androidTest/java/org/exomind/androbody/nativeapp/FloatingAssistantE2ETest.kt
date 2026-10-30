package org.exomind.androbody.nativeapp

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.exomind.androbody.nativeapp.config.ModelProtocol
import org.exomind.androbody.nativeapp.config.ModelSettings
import org.exomind.androbody.nativeapp.ui.OverlayService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Uses the real system overlay window with a private MockWebServer response. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FloatingAssistantE2ETest {
    @get:Rule val compose = createEmptyComposeRule()

    @After fun stopOverlayAfterEachTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.stopService(Intent(context, OverlayService::class.java))
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (OverlayService.active.value && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25)
        }
    }

    @Test fun expandedCardShowsMarkdownAndPersistsPositionAcrossServiceRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".verification"))
        assertTrue("Verification-only SYSTEM_ALERT_WINDOW app-op must be granted by the harness",
            Settings.canDrawOverlays(context))

        loopbackServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "POST" && request.path == "/v1/messages")
                        markdownSse("悬浮卡片已显示最新回复。")
                    else MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("{}")
            }
            runBlocking {
                withTimeout(30_000) { context.graph.native.state.first { !it.initializing } }
                context.graph.profiles.save(ModelSettings(ModelProtocol.ANTHROPIC, server.loopbackUrl("/v1").toString(),
                    "fixture-overlay-key", "fixture-model", allowInsecureLocal = true))
                context.graph.preferences.update { it.copy(protocol = ModelProtocol.ANTHROPIC) }
                context.graph.native.freshContext()
            }

            val device = UiDevice.getInstance(instrumentation)
            val evidence = File(context.filesDir, "qa/evidence").apply { mkdirs() }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("nav-BODY").performClick()
                compose.onNodeWithTag("body-list").performScrollToNode(hasText("悬浮助手", substring = false))
                compose.onNodeWithTag("overlay-toggle").performClick()
                compose.waitUntil(5_000) { OverlayService.active.value }

                compose.onNodeWithTag("nav-ASSISTANT").performClick()
                compose.onNodeWithTag("assistant-composer").performTextReplacement("检查一条受控悬浮卡片回复。")
                compose.onNodeWithTag("assistant-send").performClick()
                compose.waitUntil(15_000) {
                    context.graph.native.state.value.entries.any { it.role == "assistant" && it.text.contains("悬浮卡片") }
                }

                device.pressBack()
                compose.waitForIdle()
                val collapsed = device.wait(Until.findObject(By.descContains("点按查看最新消息")), 5_000)
                assertNotNull("Collapsed floating entry should stay accessible", collapsed)
                assertTrue("Collapsed floating entry should expose a click action", collapsed!!.isClickable)
                val initialBounds = collapsed.visibleBounds
                device.dumpWindowHierarchy(File(evidence, "floating-assistant-before-expand.xml"))
                collapsed.click()
                val expandedByAccessibilityClick = awaitOverlayExpanded(750)
                if (!expandedByAccessibilityClick)
                    device.click(initialBounds.centerX(), initialBounds.centerY())
                val expandedAfterTouchFallback = awaitOverlayExpanded()
                compose.waitForIdle()
                val touchLog = device.executeShellCommand("logcat -d -s AndroMorphOverlay:D")
                File(evidence, "floating-assistant-touch-state.json").writeText(JSONObject()
                    .put("expandedAfterAccessibilityClick", expandedByAccessibilityClick)
                    .put("expandedAfterTouchFallback", expandedAfterTouchFallback)
                    .put("touchLog", touchLog).toString(2))
                device.dumpWindowHierarchy(File(evidence, "floating-assistant-after-click.xml"))
                assertTrue("Native/physical tap should expand the floating card; log=$touchLog", expandedAfterTouchFallback)
                assertNotNull("Native click target should expand the card",
                    device.wait(Until.findObject(By.text("打开助手")), 5_000))
                assertNotNull("Expanded card should show the latest Markdown reply",
                    device.wait(Until.findObject(By.textContains("悬浮卡片")), 5_000))
                assertTrue(device.takeScreenshot(File(evidence, "floating-assistant-expanded-before-drag.png")))
                device.findObject(By.desc("收起悬浮卡片")).click()
                val collapsedAgain = device.wait(Until.findObject(By.descContains("点按查看最新消息")), 5_000)
                assertNotNull("Card should return to the collapsed entry", collapsedAgain)
                val startingBounds = collapsedAgain!!.visibleBounds
                val display = context.resources.displayMetrics
                val dragX = if (startingBounds.centerX() > display.widthPixels / 2) -90 else 90
                val targetX = (startingBounds.centerX() + dragX).coerceIn(8, display.widthPixels - 8)
                val targetY = (startingBounds.centerY() + 120).coerceIn(8, display.heightPixels - 8)
                assertTrue("Dragging the bubble should produce a touch gesture",
                    device.swipe(startingBounds.centerX(), startingBounds.centerY(), targetX, targetY, 12))
                device.waitForIdle()
                assertTrue(device.takeScreenshot(File(evidence, "floating-assistant-after-drag.png")))
                device.dumpWindowHierarchy(File(evidence, "floating-assistant-after-drag.xml"))
                val moved = device.wait(Until.findObject(By.descContains("点按查看最新消息")), 5_000)
                assertNotNull("Dragged floating entry should remain available", moved)
                val committedBounds = moved!!.visibleBounds
                assertTrue("One drag should commit a visible position change; start=$startingBounds target=($targetX,$targetY) final=$committedBounds",
                    abs(committedBounds.centerX() - startingBounds.centerX()) > 20 ||
                        abs(committedBounds.centerY() - startingBounds.centerY()) > 20)
                moved.click()
                assertTrue(device.takeScreenshot(File(evidence, "floating-assistant-after-expand.png")))
                device.dumpWindowHierarchy(File(evidence, "floating-assistant-after-expand.xml"))
                assertNotNull("Expanded card should expose its open action",
                    device.wait(Until.findObject(By.text("打开助手")), 5_000))
                assertNotNull("Expanded card should identify its current response",
                    device.wait(Until.findObject(By.text("最新回复")), 5_000))
                assertNotNull("Expanded card should render the latest Markdown reply",
                    device.wait(Until.findObject(By.textContains("悬浮卡片")), 5_000))
                assertTrue(device.takeScreenshot(File(evidence, "floating-assistant-markdown.png")))

                device.findObject(By.desc("关闭悬浮入口")).click()
                compose.waitUntil(5_000) { !OverlayService.active.value }

                compose.onNodeWithTag("nav-BODY").performClick()
                compose.onNodeWithTag("overlay-toggle").performClick()
                compose.waitUntil(5_000) { OverlayService.active.value }
                val restored = device.wait(Until.findObject(By.descContains("点按查看最新消息")), 5_000)
                assertNotNull("The user-selected bubble position should survive a service restart", restored)
                val restoredBounds = restored!!.visibleBounds
                assertTrue("The restored location should match the last committed position",
                    abs(restoredBounds.centerX() - committedBounds.centerX()) <= 5 &&
                        abs(restoredBounds.centerY() - committedBounds.centerY()) <= 5)
                assertFalse("A restarted service should restore to the collapsed state", OverlayService.expanded.value)
                restored.click()
                val expandedByAccessibility = awaitOverlayExpanded(750)
                if (!expandedByAccessibility)
                    device.click(restoredBounds.centerX(), restoredBounds.centerY())
                val expandedAfterFallback = awaitOverlayExpanded()
                compose.waitForIdle()
                val restartTouchLog = device.executeShellCommand("logcat -d -s AndroMorphOverlay:D")
                File(evidence, "floating-assistant-restart-touch-state.json").writeText(JSONObject()
                    .put("expandedByAccessibility", expandedByAccessibility)
                    .put("expandedAfterTouchFallback", expandedAfterFallback)
                    .put("touchLog", restartTouchLog).toString(2))
                device.dumpWindowHierarchy(File(evidence, "floating-assistant-after-restart-expand.xml"))
                assertTrue(device.takeScreenshot(File(evidence, "floating-assistant-after-restart-expand.png")))
                assertTrue("Restored entry should expand after restart; log=$restartTouchLog", expandedAfterFallback)
                val closeAction = device.wait(Until.findObject(By.desc("关闭悬浮入口")), 5_000)
                assertNotNull("Restored entry should still expose its close action", closeAction)
                closeAction!!.click()
                compose.waitUntil(5_000) { !OverlayService.active.value }

            }
        }
    }

    @Test fun openActionReturnsToTheExistingConversationFromBackground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".verification"))
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(context.filesDir, "qa/evidence").apply { mkdirs() }
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        assertNotNull("Conversation should be visible before backgrounding",
            device.wait(Until.findObject(By.textContains("检查一条受控悬浮卡片回复")), 5_000))
        try {
            context.startForegroundService(Intent(context, OverlayService::class.java))
            val collapsed = device.wait(Until.findObject(By.descContains("点按查看最新消息")), 5_000)
            assertNotNull("User-started overlay entry should be available", collapsed)
            device.pressHome()
            device.waitForIdle()
            val bubbleAfterHome = device.wait(Until.findObject(By.descContains("点按查看最新消息")), 5_000)
            assertNotNull("Overlay entry should remain available over the launcher", bubbleAfterHome)
            val collapsedBounds = bubbleAfterHome!!.visibleBounds
            bubbleAfterHome.click()
            val expandedByAccessibility = awaitOverlayExpanded(750)
            if (!expandedByAccessibility)
                device.click(collapsedBounds.centerX(), collapsedBounds.centerY())
            val expandedAfterTouchFallback = awaitOverlayExpanded()
            device.waitForIdle()
            val expandedAfterBackgroundClick = expandedAfterTouchFallback
            val touchLog = device.executeShellCommand("logcat -d -s AndroMorphOverlay:D")
            File(evidence, "floating-assistant-background-touch-state.json").writeText(JSONObject()
                .put("expandedAfterTouch", expandedAfterBackgroundClick).put("touchLog", touchLog).toString(2))
            device.dumpWindowHierarchy(File(evidence, "floating-assistant-background-expanded.xml"))
            assertTrue(device.takeScreenshot(File(evidence, "floating-assistant-background-expanded.png")))
            assertTrue("Touching the card should expand it over the launcher; log=$touchLog", expandedAfterBackgroundClick)
            assertNotNull("Expanded card should expose its open action",
                device.wait(Until.findObject(By.text("打开助手")), 5_000))
            device.findObject(By.text("打开助手")).click()
            assertNotNull("Open should foreground the existing conversation",
                device.wait(Until.findObject(By.textContains("检查一条受控悬浮卡片回复")), 5_000))
            val close = device.wait(Until.findObject(By.desc("关闭悬浮入口")), 5_000)
            assertNotNull("The overlay should stay independently closable", close)
            close!!.click()
            assertNotNull("Closing the overlay should leave the conversation visible",
                device.wait(Until.findObject(By.textContains("检查一条受控悬浮卡片回复")), 5_000))
        } finally {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private fun markdownSse(text: String): MockResponse {
        val events = listOf(
            JSONObject().put("type", "message_start").put("message",
                JSONObject().put("id", "fixture-overlay").put("role", "assistant").put("model", "fixture-model")
                    .put("content", JSONArray()).put("usage", JSONObject().put("input_tokens", 1))),
            JSONObject().put("type", "content_block_start").put("index", 0)
                .put("content_block", JSONObject().put("type", "text").put("text", "")),
            JSONObject().put("type", "content_block_delta").put("index", 0)
                .put("delta", JSONObject().put("type", "text_delta").put("text", "**" + text + "**")),
            JSONObject().put("type", "content_block_stop").put("index", 0),
            JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "end_turn")
                .put("stop_sequence", JSONObject.NULL)).put("usage", JSONObject().put("output_tokens", 4)),
            JSONObject().put("type", "message_stop"),
        )
        val body = events.joinToString("") { event ->
            "event: " + event.getString("type") + "\ndata: " + event.toString() + "\n\n"
        }
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream; charset=utf-8")
            .setChunkedBody(body, 32).throttleBody(32, 40, TimeUnit.MILLISECONDS)
    }

    private fun awaitOverlayExpanded(timeoutMillis: Long = 5_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (OverlayService.expanded.value) return true
            SystemClock.sleep(25)
        }
        return OverlayService.expanded.value
    }
}
