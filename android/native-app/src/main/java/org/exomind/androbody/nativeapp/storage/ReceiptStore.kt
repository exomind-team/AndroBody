package org.exomind.androbody.nativeapp.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.exomind.androbody.nativeapp.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Audit metadata only: never arguments, model transcripts, keys or notification bodies. */
data class ReceiptEntry(val id: String, val requestId: String, val caller: String, val tool: String,
    val status: String, val time: Long, val evidence: String)
class ReceiptStore(context: Context) : ReceiptJournal {
    private val storage = EncryptedStore(context, "receipts")
    private val mutable = MutableStateFlow<List<ReceiptEntry>>(emptyList())
    val entries: StateFlow<List<ReceiptEntry>> = mutable
    var loadError: Boolean = false; private set
    init {
        try {
            val list = storage.read()?.optJSONArray("entries") ?: JSONArray()
            mutable.value = list.objects().map {
                ReceiptEntry(it.getString("id"), it.getString("requestId"), it.getString("caller"), it.getString("tool"),
                    if (it.getString("status") == "RUNNING") "UNKNOWN" else it.getString("status"),
                    it.getLong("time"), if (it.getString("status") == "RUNNING") "上次进程结束前未取得最终回执" else it.getString("evidence"))
            }.takeLast(LIMIT)
        } catch (_: Exception) { loadError = true }
    }
    @Synchronized override fun begin(invocation: Invocation) {
        check(!loadError) { "原审计存储无法解密；不得静默覆盖" }
        append(ReceiptEntry(invocation.id, invocation.requestId, invocation.principal.id, invocation.capability,
            "RUNNING", System.currentTimeMillis(), "请求已经登记，尚未确认结果"))
    }
    @Synchronized override fun finish(invocation: Invocation, receipt: Receipt) {
        check(!loadError)
        append(ReceiptEntry(receipt.invocationId, receipt.requestId, invocation.principal.id, receipt.capability,
            receipt.outcome.name, receipt.observedAt, receipt.evidence.take(512)))
    }
    private fun append(entry: ReceiptEntry) {
        val next = (mutable.value.filterNot { it.id == entry.id } + entry).takeLast(LIMIT)
        storage.write(JSONObject().put("entries", JSONArray(next.map { jsonObject("id" to it.id, "requestId" to it.requestId,
            "caller" to it.caller, "tool" to it.tool, "status" to it.status, "time" to it.time, "evidence" to it.evidence) })))
        mutable.value = next
    }
    fun snapshot(): JSONObject = jsonObject("retainedLimit" to LIMIT,
        "entries" to JSONArray(entries.value.map { jsonObject("id" to it.id, "capability" to it.tool,
            "status" to it.status, "observedAtEpochMs" to it.time, "evidence" to it.evidence) }))
    companion object { const val LIMIT = 200 }
}
