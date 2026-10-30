package org.exomind.androbody.nativeapp.ui

import android.view.InputDevice
import android.os.Build
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import org.exomind.androbody.nativeapp.config.ComposerSendMode

/** Input contract is pure enough to test without executing device actions. */
object ComposerKeys {
    fun isSend(
        keyCode: Int,
        actionUp: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        composing: Boolean,
        mode: ComposerSendMode,
    ): Boolean = actionUp && keyCode == android.view.KeyEvent.KEYCODE_ENTER && !alt && !shift && !composing && when (mode) {
        ComposerSendMode.CTRL_ENTER -> ctrl
        ComposerSendMode.ENTER -> !ctrl
    }
}

/** AVDs can advertise a virtual QWERTY device; only an attached non-virtual keyboard gets shortcut hints. */
fun hasExternalHardwareKeyboard(): Boolean = InputDevice.getDeviceIds().any { id ->
    InputDevice.getDevice(id)?.let { device ->
        !device.isVirtual && (Build.VERSION.SDK_INT < 29 || device.isExternal) &&
            (device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
    } == true
}

class InputContext { var editing by mutableStateOf(false) }
val LocalInputContext = staticCompositionLocalOf { InputContext() }

fun Modifier.submitOnShortcut(value: TextFieldValue, mode: ComposerSendMode, onSubmit: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val matches = event.key == Key.Enter && !event.isAltPressed && !event.isShiftPressed && when (mode) {
        ComposerSendMode.CTRL_ENTER -> event.isCtrlPressed
        ComposerSendMode.ENTER -> !event.isCtrlPressed
    }
    if (matches && value.composition == null) {
        if (ComposerKeys.isSend(event.nativeKeyEvent.keyCode, event.type == KeyEventType.KeyUp,
                event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, value.composition != null, mode)) onSubmit()
        true
    } else false
}
