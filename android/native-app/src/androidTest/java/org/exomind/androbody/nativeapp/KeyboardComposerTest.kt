package org.exomind.androbody.nativeapp

import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.exomind.androbody.nativeapp.ui.MorphTheme
import org.exomind.androbody.nativeapp.ui.submitOnShortcut
import org.exomind.androbody.nativeapp.config.ComposerSendMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure test surface: no real API key, native task or device action is used. */
@OptIn(ExperimentalTestApi::class)
class KeyboardComposerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun ctrlEnterSubmitsExactlyOnceThroughProductionModifier() {
        var sends = 0
        compose.setContent {
            var value by remember { mutableStateOf(TextFieldValue("测试")) }
            MorphTheme("light") { OutlinedTextField(value, { value = it },
                modifier = Modifier.testTag("composer").submitOnShortcut(value, ComposerSendMode.CTRL_ENTER) { sends++ }) }
        }
        compose.onNodeWithTag("composer").performClick().performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.Enter); keyUp(Key.CtrlLeft)
        }
        compose.runOnIdle { assertEquals(1, sends) }
    }
    @Test fun composingChineseTextDoesNotSubmit() {
        var sends = 0
        compose.setContent {
            val value = TextFieldValue("zhong", composition = TextRange(0, 5))
            MorphTheme("dark") { OutlinedTextField(value, {},
                modifier = Modifier.testTag("composer").submitOnShortcut(value, ComposerSendMode.CTRL_ENTER) { sends++ }) }
        }
        compose.onNodeWithTag("composer").performClick().performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.Enter); keyUp(Key.CtrlLeft)
        }
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun enterModeSendsWithBareEnterAndKeepsShiftEnterForNewLine() {
        var sends = 0
        compose.setContent {
            var value by remember { mutableStateOf(TextFieldValue("测试")) }
            MorphTheme("light") { OutlinedTextField(value, { value = it },
                modifier = Modifier.testTag("composer").submitOnShortcut(value, ComposerSendMode.ENTER) { sends++ }) }
        }
        compose.onNodeWithTag("composer").performClick().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, sends) }
        compose.onNodeWithTag("composer").performKeyInput {
            keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft)
        }
        compose.runOnIdle { assertEquals("Shift+Enter must not dispatch a send", 1, sends) }
    }

    @Test fun enterModeDoesNotSendOnCtrlEnter() {
        var sends = 0
        compose.setContent {
            var value by remember { mutableStateOf(TextFieldValue("测试")) }
            MorphTheme("dark") { OutlinedTextField(value, { value = it },
                modifier = Modifier.testTag("composer").submitOnShortcut(value, ComposerSendMode.ENTER) { sends++ }) }
        }
        compose.onNodeWithTag("composer").performClick().performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.Enter); keyUp(Key.CtrlLeft)
        }
        compose.runOnIdle { assertEquals(0, sends) }
    }
}
