package org.exomind.androbody.nativeapp.events

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.exomind.androbody.nativeapp.config.ModelSettings
import org.exomind.androbody.nativeapp.config.EndpointPolicy
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.storage.EncryptedStore
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

/** A bounded event inbox, not a second general Agent Session store. */
data class TriggerItem(val id: String, val fingerprint: String, val packageName: String,
    val title: String, val body: String, val route: String, val createdAt: Long,
    val status: String = "PENDING", val summary: String = "")
class TriggerInbox(context: Context) {
    private val store = EncryptedStore(context, "trigger-inbox")
    private val mutable = MutableStateFlow<List<TriggerItem>>(emptyList())
    private var acceptedAt = mutableListOf<Long>()
    val items: StateFlow<List<TriggerItem>> = mutable
    var readError: String? = null; private set
    init {
        try {
            val stored = store.read()
            acceptedAt = stored?.optJSONArray("acceptedAt")?.let { times -> (0 until times.length()).map { times.getLong(it) }.toMutableList() } ?: mutableListOf()
            mutable.value = (stored?.optJSONArray("items") ?: JSONArray()).objects().map {
                TriggerItem(it.getString("id"), it.getString("fingerprint"), it.getString("package"),
                    it.getString("title"), it.getString("body"), it.getString("route"), it.getLong("createdAt"),
                    if (it.getString("status") == "RUNNING") "UNKNOWN" else it.getString("status"), it.optString("summary"))
            }
        } catch (_: Exception) { readError = "事件记录无法读取，未覆盖原件" }
    }
    @Synchronized fun add(source: String, notificationKey: String, title: String, body: String, route: String): TriggerItem? {
        check(readError == null) { "Event store unavailable" }
        val digest = sha256("$source\n$notificationKey\n$title\n$body")
        if (mutable.value.any { it.fingerprint == digest }) return null
        val now = System.currentTimeMillis()
        acceptedAt.removeAll { now - it >= 3_600_000L }
        if (acceptedAt.size >= 10)
            throw BodyFailure("EVENT_RATE_LIMIT", "每小时最多接收十条自动摘要，未继续外发")
        val active = mutable.value.filter { it.status in setOf("PENDING", "RUNNING") }
        if (active.size >= MAX_ITEMS) throw BodyFailure("EVENT_QUEUE_FULL", "事件队列已满，未覆盖在途任务")
        val retained = mutable.value.filterNot { it.status in setOf("PENDING", "RUNNING") }.takeLast(MAX_ITEMS - active.size - 1)
        val item = TriggerItem(UUID.randomUUID().toString(), digest, source, title.take(100), body.take(1000), route, now)
        acceptedAt.add(now)
        save((retained + active + item).sortedBy { it.createdAt }); return item
    }
    @Synchronized fun claim(id: String): TriggerItem? {
        val item = mutable.value.firstOrNull { it.id == id && it.status == "PENDING" } ?: return null
        save(mutable.value.map { if (it.id == id) it.copy(status = "RUNNING") else it })
        return item
    }
    @Synchronized fun finish(id: String, status: String, summary: String) {
        // Revocation wins against late model responses. An event never schedules itself again.
        save(mutable.value.map { if (it.id == id && it.status == "RUNNING") it.copy(status = status, summary = summary.take(6000), body = "") else it })
    }
    @Synchronized fun revokePending() {
        save(mutable.value.map { if (it.status in setOf("PENDING", "RUNNING"))
            it.copy(status = if (it.status == "RUNNING") "UNKNOWN" else "CANCELLED", body = "", summary = "用户关闭自动处理；已外发内容不能撤回") else it })
    }
    @Synchronized fun remove(id: String) { save(mutable.value.filterNot { it.id == id }) }
    private fun save(items: List<TriggerItem>) {
        check(readError == null) { "Event store unavailable" }
        store.write(jsonObject("acceptedAt" to JSONArray(acceptedAt), "items" to JSONArray(items.map { jsonObject("id" to it.id, "fingerprint" to it.fingerprint,
            "package" to it.packageName, "title" to it.title, "body" to it.body, "route" to it.route,
            "createdAt" to it.createdAt, "status" to it.status, "summary" to it.summary) })))
        mutable.value = items
    }
    companion object { const val MAX_ITEMS = 30 }
}
fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 255) }
fun ModelSettings.routeFingerprint(): String = sha256("${protocol.name}\n${EndpointPolicy.endpoint(this)}\n$model")
