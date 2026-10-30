package org.exomind.androbody.nativeapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import androidx.core.view.WindowCompat
import org.exomind.androbody.nativeapp.config.ComposerSendMode

/** Actual narrow emulator surfaces; screenshots are Agent inspection evidence, not human acceptance. */
class VisualSurfaceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun narrowLightAndDarkSurfacesKeepPrimaryActionsReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".verification"))
        val device = UiDevice.getInstance(instrumentation)
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        val original = device.executeShellCommand("wm size")
        val priorOverride = Regex("Override size: (\\d+x\\d+)").find(original)?.groupValues?.get(1)
        val out = File(context.filesDir, "qa/evidence").apply { mkdirs() }
        try {
            device.executeShellCommand("wm size 840x1800")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (theme in listOf("light", "dark")) {
                    runBlocking { context.graph.preferences.update { it.copy(theme = theme) } }
                    for (page in listOf("ASSISTANT", "SETTINGS", "RECORDS")) {
                        compose.onNodeWithTag("nav-$page").performClick()
                        when (page) {
                            "ASSISTANT" -> {
                                compose.onNodeWithTag("assistant-composer").assertIsDisplayed()
                                for (label in listOf("设备状态", "能力清单", "模型连接")) {
                                    compose.onNodeWithText(label, substring = false).performScrollTo().assertIsDisplayed()
                                }
                            }
                            "SETTINGS" -> {
                                compose.onNodeWithTag("model-save").performScrollTo().assertIsDisplayed()
                                val bounds = compose.onNodeWithTag("model-save").fetchSemanticsNode().boundsInRoot
                                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                                assertTrue(bounds.left >= root.left && bounds.right <= root.right)
                                compose.onNodeWithTag("send-mode-enter").performScrollTo().performClick()
                                compose.waitUntil(5_000) { context.graph.preferences.state.value.composerSendMode == ComposerSendMode.ENTER }
                                assertEquals(ComposerSendMode.ENTER, context.graph.preferences.state.value.composerSendMode)
                                compose.onNodeWithTag("send-mode-ctrl-enter").performScrollTo().performClick()
                                compose.waitUntil(5_000) { context.graph.preferences.state.value.composerSendMode == ComposerSendMode.CTRL_ENTER }
                                assertEquals(ComposerSendMode.CTRL_ENTER, context.graph.preferences.state.value.composerSendMode)
                            }
                            "RECORDS" -> {
                                compose.onNodeWithText("网络", substring = false).performClick()
                                compose.onNodeWithTag("diagnostics-export").assertIsDisplayed()
                            }
                        }
                        compose.waitForIdle(); device.waitForIdle()
                        scenario.onActivity { activity ->
                            assertEquals(theme == "light", WindowCompat.getInsetsController(activity.window,
                                activity.window.decorView).isAppearanceLightStatusBars)
                        }
                        assertTrue(device.takeScreenshot(File(out, "narrow-$theme-$page.png")))
                    }
                }
            }
        } finally {
            device.executeShellCommand("wm size ${priorOverride ?: "reset"}")
        }
    }
}
