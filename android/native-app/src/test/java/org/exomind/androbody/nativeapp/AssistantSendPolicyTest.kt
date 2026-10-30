package org.exomind.androbody.nativeapp

import org.exomind.androbody.nativeapp.runtime.NativeState
import org.exomind.androbody.nativeapp.ui.canSubmitAssistantMessage
import org.junit.Assert.*
import org.junit.Test

class AssistantSendPolicyTest {
    @Test fun interruptedSessionAcceptsAUserRecoveryMessageWhenRequestIdentityIsKnown() {
        assertTrue(canSubmitAssistantMessage(NativeState(interrupted = true, unresolvedRequestId = "request-1"), "继续"))
    }

    @Test fun unsafeOrUnavailableStatesDoNotSubmit() {
        assertFalse(canSubmitAssistantMessage(NativeState(running = true), "继续"))
        assertFalse(canSubmitAssistantMessage(NativeState(initializing = true), "继续"))
        assertFalse(canSubmitAssistantMessage(NativeState(sessionArchived = true), "继续"))
        assertFalse(canSubmitAssistantMessage(NativeState(), "   "))
    }

    @Test fun interruptedSessionWithMissingRequestIdStillAcceptsReadOnlyRecoveryMessage() {
        assertTrue(canSubmitAssistantMessage(NativeState(interrupted = true), "核对最近回执"))
    }
}
