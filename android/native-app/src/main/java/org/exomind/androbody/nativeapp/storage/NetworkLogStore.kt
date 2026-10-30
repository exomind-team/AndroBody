package org.exomind.androbody.nativeapp.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.exomind.androbody.nativeapp.core.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.exomind.androbody.nativeapp.core.objects
import java.util.UUID

data class NetworkLogEntry(
    val id: String,
    val requestId: String,
    val protocol: String,
    val url: String,
    val status: String,
    val httpStatus: Int?,
    val durationMs: Long,
    val model: String,
    val errorCode: String?,
    val at: Long,
)

/** Redacted transport trace: URL and outcome only; never credentials, body, or response text. */
class NetworkLogStore(context: Context) {
    private val storage = EncryptedStore(context, "network-logs")
    private val mutable = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val entries: StateFlow<List<NetworkLogEntry>> = mutable
    init { runCatching {
        val list = storage.read()?.optJSONArray("entries") ?: JSONArray()
        mutable.value = list.objects().map(::decode).takeLast(LIMIT)
    } }
    @Synchronized fun append(entry: NetworkLogEntry) {
        val next = (mutable.value + entry).takeLast(LIMIT)
        storage.write(JSONObject().put("entries", JSONArray(next.map(::encode))))
        mutable.value = next
    }
    @Synchronized fun clear() { storage.write(JSONObject().put("entries", JSONArray())); mutable.value = emptyList() }
    fun snapshot(): JSONObject = jsonObject("entries" to JSONArray(entries.value.map(::encode)))
    private fun encode(entry: NetworkLogEntry) = jsonObject(
        "id" to entry.id, "requestId" to entry.requestId, "protocol" to entry.protocol,
        "url" to entry.url, "status" to entry.status, "httpStatus" to entry.httpStatus,
        "durationMs" to entry.durationMs, "model" to entry.model, "errorCode" to entry.errorCode,
        "at" to entry.at)
    private fun decode(value: JSONObject) = NetworkLogEntry(
        value.getString("id"), value.getString("requestId"), value.getString("protocol"),
        value.getString("url"), value.getString("status"), value.optInt("httpStatus").takeIf { value.has("httpStatus") && !value.isNull("httpStatus") },
        value.optLong("durationMs"), value.getString("model"), value.optString("errorCode").ifBlank { null }, value.getLong("at"))
    companion object { const val LIMIT = 200 }
}
