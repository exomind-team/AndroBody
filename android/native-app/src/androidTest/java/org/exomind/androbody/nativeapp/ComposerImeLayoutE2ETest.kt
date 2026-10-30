package org.exomind.androbody.nativeapp

import android.provider.Settings
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Verifies the software keyboard gets only the input affordances it can use. */
class ComposerImeLayoutE2ETest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test fun softKeyboardHidesHardwareHintAndBottomNavigationAndStaysNearComposer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".verification"))
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(context.filesDir, "qa/evidence").apply { mkdirs() }

        compose.onNodeWithTag("assistant-composer").performClick()
        val imePackage = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        assertNotNull("Emulator must expose its selected software keyboard", imePackage)
        val keyboardNode = device.wait(Until.findObject(By.pkg(imePackage!!)), 5_000)
        assertNotNull("The software keyboard should open for the composer", keyboardNode)
        compose.waitForIdle()

        compose.onAllNodesWithTag("nav-ASSISTANT").assertCountEquals(0)
        compose.onAllNodesWithText("Ctrl+Enter 发送 · Enter 换行", substring = false).assertCountEquals(0)
        compose.onAllNodesWithText("Enter 发送 · Shift+Enter 换行", substring = false).assertCountEquals(0)
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText").pkg(context.packageName)), 5_000)
        assertNotNull("The native assistant composer should remain visible above the keyboard", field)
        val keyboardTop = device.findObjects(By.pkg(imePackage)).minOf { it.visibleBounds.top }
        val gapDp = (keyboardTop - field!!.visibleBounds.bottom) / context.resources.displayMetrics.density
        assertTrue(device.takeScreenshot(File(evidence, "composer-soft-keyboard.png")))
        File(evidence, "composer-soft-keyboard.json").writeText(JSONObject()
            .put("imePackage", imePackage)
            .put("composerBounds", field.visibleBounds.toString())
            .put("keyboardTop", keyboardTop)
            .put("composerToKeyboardGapDp", gapDp)
            .put("shortcutHintVisible", false)
            .put("bottomNavigationVisible", false)
            .toString(2))
        assertTrue("Composer-to-keyboard gap should stay within 64dp, observed ${gapDp}dp", gapDp in 0.0..64.0)
    }
}
