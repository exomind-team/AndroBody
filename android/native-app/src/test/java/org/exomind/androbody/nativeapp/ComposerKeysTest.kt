package org.exomind.androbody.nativeapp

import org.exomind.androbody.nativeapp.ui.ComposerKeys
import org.exomind.androbody.nativeapp.config.ComposerSendMode
import org.junit.Assert.*
import org.junit.Test

class ComposerKeysTest {
    @Test fun onlyCtrlEnterReleaseWithoutImeCompositionSubmits() {
        assertTrue(ComposerKeys.isSend(66, true, true, false, false, false, ComposerSendMode.CTRL_ENTER))
        assertFalse(ComposerKeys.isSend(66, true, true, false, false, true, ComposerSendMode.CTRL_ENTER))
        assertFalse(ComposerKeys.isSend(66, false, true, false, false, false, ComposerSendMode.CTRL_ENTER))
        assertFalse(ComposerKeys.isSend(66, true, false, false, false, false, ComposerSendMode.CTRL_ENTER))
        assertFalse(ComposerKeys.isSend(66, true, true, true, false, false, ComposerSendMode.CTRL_ENTER))
        assertFalse(ComposerKeys.isSend(66, true, true, false, true, false, ComposerSendMode.CTRL_ENTER))
    }

    @Test fun enterModeAndCtrlEnterModeDoNotCrossTrigger() {
        assertTrue(ComposerKeys.isSend(66, true, false, false, false, false, ComposerSendMode.ENTER))
        assertFalse(ComposerKeys.isSend(66, true, true, false, false, false, ComposerSendMode.ENTER))
        assertFalse(ComposerKeys.isSend(66, true, false, false, true, false, ComposerSendMode.ENTER))
    }
}
