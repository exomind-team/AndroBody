package org.exomind.androbody.nativeapp.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class PendingApproval(val invocation: Invocation, val capability: Capability, val expiresAt: Long)
class ApprovalQueue : ApprovalPort {
    private val pendingAnswers = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val mutable = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pending: StateFlow<List<PendingApproval>> = mutable
    override suspend fun approve(invocation: Invocation, capability: Capability): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingAnswers[invocation.id] = answer
        mutable.update { it + PendingApproval(invocation, capability, System.currentTimeMillis() + TIMEOUT_MS) }
        return try { withTimeoutOrNull(TIMEOUT_MS) { answer.await() } ?: false }
        finally { pendingAnswers.remove(invocation.id); mutable.update { list -> list.filterNot { it.invocation.id == invocation.id } } }
    }
    fun answer(id: String, allowed: Boolean) {
        val item = mutable.value.firstOrNull { it.invocation.id == id } ?: return
        pendingAnswers[id]?.complete(allowed && System.currentTimeMillis() < item.expiresAt)
    }
    fun denyAll() { pendingAnswers.values.forEach { it.complete(false) } }
    companion object { const val TIMEOUT_MS = 60_000L }
}
