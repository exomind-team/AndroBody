package org.exomind.androbody.nativeapp.core

import org.exomind.androbody.nativeapp.config.AppPreferences
import org.exomind.androbody.nativeapp.config.NativeApprovalMode
import org.exomind.androbody.nativeapp.config.NativeCapabilityChoice

/** Pure local-agent approval rules; OS permissions and gateway grants remain separate gates. */
object NativeApprovalRules {
    fun denial(principal: Principal, capability: Capability, preferences: AppPreferences): String? =
        if (principal.origin == Origin.NATIVE &&
            preferences.nativeCapabilityChoices[capability.name] == NativeCapabilityChoice.DENY)
            "用户已拒绝此项能力"
        else null

    fun requiresApproval(principal: Principal, capability: Capability, preferences: AppPreferences): Boolean {
        if (principal.origin != Origin.NATIVE) return capability.needsConfirmation
        return when (preferences.nativeCapabilityChoices[capability.name]) {
            NativeCapabilityChoice.ALLOW, NativeCapabilityChoice.DENY -> false
            NativeCapabilityChoice.ASK -> true
            null -> when (preferences.nativeApprovalMode) {
                NativeApprovalMode.ASK_SENSITIVE -> capability.needsConfirmation
                NativeApprovalMode.ALLOW_READS -> capability.needsConfirmation &&
                    capability.effect !in setOf(Effect.READ_DEVICE, Effect.READ_WORKSPACE)
                NativeApprovalMode.ALLOW_ALL -> false
            }
        }
    }
}
