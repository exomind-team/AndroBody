package org.exomind.androbody.nativeapp.core

import org.exomind.androbody.nativeapp.config.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeApprovalRulesTest {
    private fun capability(effect: Effect = Effect.USER_VISIBLE, confirmation: Boolean = true) = Capability(
        name = "am_fixture", title = "Fixture", description = "Local fixture",
        parameters = JSONObject(), effect = effect, gatewayEligible = false, needsConfirmation = confirmation,
    )
    private val native = Principal("native:user", Origin.NATIVE)
    private val gateway = Principal("local-client:1", Origin.GATEWAY)

    @Test fun allowAllOnlyChangesNativeApprovals() {
        val prefs = AppPreferences(nativeApprovalMode = NativeApprovalMode.ALLOW_ALL)
        val cap = capability()
        assertFalse(NativeApprovalRules.requiresApproval(native, cap, prefs))
        assertTrue(NativeApprovalRules.requiresApproval(gateway, cap, prefs))
    }

    @Test fun allowReadsRemovesOnlyReadCapabilityPrompts() {
        val prefs = AppPreferences(nativeApprovalMode = NativeApprovalMode.ALLOW_READS)
        assertFalse(NativeApprovalRules.requiresApproval(native, capability(Effect.READ_DEVICE), prefs))
        assertFalse(NativeApprovalRules.requiresApproval(native, capability(Effect.READ_WORKSPACE), prefs))
        assertTrue(NativeApprovalRules.requiresApproval(native, capability(Effect.WRITE_WORKSPACE), prefs))
        assertTrue(NativeApprovalRules.requiresApproval(native, capability(Effect.USER_VISIBLE), prefs))
    }

    @Test fun perCapabilityAskAllowAndDenyOverrideGlobalDefault() {
        val cap = capability(Effect.READ_DEVICE, confirmation = false)
        val ask = AppPreferences(nativeApprovalMode = NativeApprovalMode.ALLOW_ALL,
            nativeCapabilityChoices = mapOf(cap.name to NativeCapabilityChoice.ASK))
        val allow = ask.copy(nativeCapabilityChoices = mapOf(cap.name to NativeCapabilityChoice.ALLOW))
        val deny = ask.copy(nativeCapabilityChoices = mapOf(cap.name to NativeCapabilityChoice.DENY))
        assertTrue(NativeApprovalRules.requiresApproval(native, cap, ask))
        assertFalse(NativeApprovalRules.requiresApproval(native, cap, allow))
        assertFalse(NativeApprovalRules.requiresApproval(native, cap, deny))
        assertNotNull(NativeApprovalRules.denial(native, cap, deny))
        assertNull(NativeApprovalRules.denial(gateway, cap, deny))
    }
}
