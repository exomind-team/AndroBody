package org.exomind.androbody.nativeapp.events

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.exomind.androbody.nativeapp.core.BodyFailure
import org.exomind.androbody.nativeapp.core.jsonObject
import org.exomind.androbody.nativeapp.storage.EncryptedStore
import org.json.JSONArray

/** Notifications are local, encrypted observations; this record never enters runtime metadata logs. */
data class NotificationRecord(
    val id: String,
    val packageName: String,
    val title: String,
    val body: String,
    val postedAt: Long,
    val active: Boolean,
)

class NotificationInbox(context: Context) {
    private val store = EncryptedStore(context, "notification-inbox")
    private val mutable = MutableStateFlow<List<NotificationRecord>>(emptyList())
    val items: StateFlow<List<NotificationRecord>> = mutable
    var readError: String? = null
        private set

    init {
        try {
            val rows = store.read()?.optJSONArray("items") ?: JSONArray()
            mutable.value = (0 until rows.length()).map { rows.getJSONObject(it) }.map { row ->
                NotificationRecord(row.getString("id"), row.getString("packageName"), row.optString("title"),
                    row.optString("body"), row.getLong("postedAt"), row.optBoolean("active"))
            }.takeLast(MAX_ITEMS)
        } catch (_: Exception) {
            readError = "本机通知收件箱无法解密；未覆盖原件"
        }
    }

    @Synchronized fun observe(record: NotificationRecord) {
        check(readError == null) { "notification inbox unreadable" }
        val safe = record.copy(title = record.title.take(MAX_TITLE_CHARS), body = record.body.take(MAX_BODY_CHARS))
        val next = (mutable.value.filterNot { it.id == safe.id } + safe).sortedBy { it.postedAt }.takeLast(MAX_ITEMS)
        persist(next)
    }

    @Synchronized fun setActive(id: String, active: Boolean) {
        val next = mutable.value.map { if (it.id == id) it.copy(active = active) else it }
        if (next != mutable.value) persist(next)
    }

    /** Android's active snapshot is authoritative for current rows; retained rows stay historical. */
    @Synchronized fun replaceActive(records: List<NotificationRecord>) {
        check(readError == null) { "notification inbox unreadable" }
        val activeById = records.associateBy { it.id }
        val next = mutable.value.map { old ->
            activeById[old.id] ?: if (old.active) old.copy(active = false) else old
        }.toMutableList()
        activeById.values.forEach { current ->
            val index = next.indexOfFirst { it.id == current.id }
            if (index < 0) next += current else next[index] = current
        }
        persist(next.sortedBy { it.postedAt }.takeLast(MAX_ITEMS))
    }

    fun snapshot(limit: Int, activeOnly: Boolean): List<NotificationRecord> {
        if (limit !in 1..MAX_AGENT_ITEMS) throw BodyFailure("INVALID_ARGUMENT", "通知读取数量须为 1–50")
        return mutable.value.asSequence().filter { !activeOnly || it.active }.toList().takeLast(limit).asReversed()
    }

    private fun persist(rows: List<NotificationRecord>) {
        store.write(jsonObject("schema" to 1, "items" to JSONArray(rows.map { row ->
            jsonObject("id" to row.id, "packageName" to row.packageName, "title" to row.title,
                "body" to row.body, "postedAt" to row.postedAt, "active" to row.active)
        })))
        mutable.value = rows
    }

    companion object {
        const val MAX_ITEMS = 100
        const val MAX_AGENT_ITEMS = 50
        const val MAX_TITLE_CHARS = 256
        const val MAX_BODY_CHARS = 2_000
    }
}

object NotificationAccess {
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, NoticeListener::class.java).flattenToString()
        val raw = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return raw.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
