package org.exomind.androbody.nativeapp.runtime

import android.content.Context
import org.exomind.androbody.nativeapp.config.ModelProtocol
import org.exomind.androbody.nativeapp.core.objects
import org.exomind.androbody.nativeapp.model.ContextBudgetStatus
import org.exomind.androbody.nativeapp.storage.EncryptedStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NativeSessionInfo(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
)

data class NativeSessionSnapshot(
    val info: NativeSessionInfo,
    val entries: List<ChatEntry>,
    val history: JSONArray,
    val protocol: ModelProtocol?,
    val wasRunning: Boolean,
    val interrupted: Boolean,
    val unresolvedRequestId: String?,
    val contextBudget: ContextBudgetStatus = ContextBudgetStatus(),
)

/** Each conversation has its own encrypted record; the small index contains labels and ordering only. */
class NativeSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val index = EncryptedStore(appContext, INDEX_NAME)
    private val legacy = EncryptedStore(appContext, LEGACY_NAME)
    private val sessions = linkedMapOf<String, NativeSessionInfo>()
    private var activeId: String? = null
    private var loaded = false

    @Synchronized fun open(): NativeSessionSnapshot {
        ensureLoaded()
        val id = activeId ?: error("No active session")
        return readSnapshot(sessions[id] ?: error("Active session metadata missing"))
    }

    @Synchronized fun all(): List<NativeSessionInfo> {
        ensureLoaded()
        return sessions.values.sortedWith(compareByDescending<NativeSessionInfo> { it.id == activeId }
            .thenByDescending { it.updatedAt })
    }

    /** Exact size of the active encrypted session record on disk; null means no committed record. */
    @Synchronized fun activeRecordBytes(): Long? {
        ensureLoaded()
        return activeId?.let { sessionFile(it).storedByteCount() }
    }

    @Synchronized fun save(id: String, value: JSONObject) {
        ensureLoaded()
        val info = sessions[id] ?: error("Session no longer exists")
        sessionFile(id).write(value)
        sessions[id] = info.copy(updatedAt = System.currentTimeMillis(), title = titleFrom(value, info.title))
        writeIndex()
    }

    @Synchronized fun create(title: String = "新会话"): NativeSessionSnapshot {
        ensureLoaded()
        check(sessions.size < MAX_SESSIONS) { "会话数量达到 $MAX_SESSIONS 个上限；请先归档或删除会话" }
        val now = System.currentTimeMillis()
        val info = NativeSessionInfo(UUID.randomUUID().toString(), cleanTitle(title), now, now)
        sessionFile(info.id).write(emptyDocument())
        sessions[info.id] = info
        activeId = info.id
        writeIndex()
        return readSnapshot(info)
    }

    @Synchronized fun switch(id: String): NativeSessionSnapshot {
        ensureLoaded()
        val info = sessions[id] ?: error("会话不存在或已删除")
        activeId = id
        writeIndex()
        return readSnapshot(info)
    }

    @Synchronized fun rename(id: String, title: String): List<NativeSessionInfo> {
        ensureLoaded()
        val info = sessions[id] ?: error("会话不存在或已删除")
        sessions[id] = info.copy(title = cleanTitle(title), updatedAt = System.currentTimeMillis())
        writeIndex()
        return all()
    }

    @Synchronized fun setArchived(id: String, archived: Boolean): List<NativeSessionInfo> {
        ensureLoaded()
        val info = sessions[id] ?: error("会话不存在或已删除")
        sessions[id] = info.copy(archived = archived, updatedAt = System.currentTimeMillis())
        writeIndex()
        return all()
    }

    @Synchronized fun fork(id: String, title: String? = null): NativeSessionSnapshot {
        ensureLoaded()
        check(sessions.size < MAX_SESSIONS) { "会话数量达到 $MAX_SESSIONS 个上限" }
        val sourceInfo = sessions[id] ?: error("会话不存在或已删除")
        val source = sessionFile(id).read() ?: error("会话内容不可读；原数据未覆盖")
        val now = System.currentTimeMillis()
        val forkInfo = NativeSessionInfo(UUID.randomUUID().toString(), cleanTitle(title ?: "${sourceInfo.title} · 分支"), now, now)
        // A branch may inherit a pending-effect marker, so it cannot accidentally replay an uncertain task.
        sessionFile(forkInfo.id).write(JSONObject(source.toString()))
        sessions[forkInfo.id] = forkInfo
        activeId = forkInfo.id
        writeIndex()
        return readSnapshot(forkInfo)
    }

    @Synchronized fun delete(id: String): List<NativeSessionInfo> {
        ensureLoaded()
        check(sessions.size > 1) { "至少保留一个会话" }
        val previousActive = activeId
        if (activeId == id) activeId = sessions.keys.firstOrNull { it != id }
        // Persist removal first. A crash can leave only an unindexed encrypted orphan, never a dangling UI entry.
        val removed = sessions.remove(id) ?: error("会话不存在或已删除")
        try { writeIndex() } catch (failure: Exception) { sessions[id] = removed; activeId = previousActive; throw failure }
        if (!sessionFile(id).delete()) error("会话已从列表移除，但加密文件删除结果未确认")
        return all()
    }

    private fun ensureLoaded() {
        if (loaded) return
        val savedIndex = index.read()
        if (savedIndex == null) {
            migrateLegacyOrCreate()
        } else {
            val rows = savedIndex.optJSONArray("sessions") ?: JSONArray()
            for (row in rows.objects()) {
                val info = NativeSessionInfo(row.getString("id"), cleanTitle(row.optString("title")),
                    row.optLong("createdAt"), row.optLong("updatedAt"), row.optBoolean("archived"))
                require(isSessionId(info.id)) { "Invalid session id in encrypted index" }
                sessions[info.id] = info
            }
            activeId = savedIndex.optString("activeId").takeIf { sessions.containsKey(it) }
            if (sessions.isEmpty()) migrateLegacyOrCreate()
            else if (activeId == null) {
                activeId = sessions.values.filterNot { it.archived }.maxByOrNull { it.updatedAt }?.id
                    ?: sessions.values.maxByOrNull { it.updatedAt }?.id
                writeIndex()
            }
        }
        loaded = true
    }

    private fun migrateLegacyOrCreate() {
        val old = legacy.read()
        val now = System.currentTimeMillis()
        val legacyEntries = old?.optJSONArray("entries")?.length() ?: 0
        val info = NativeSessionInfo(UUID.randomUUID().toString(),
            if (legacyEntries == 0) "新会话" else titleFrom(old!!, "迁移的会话"), now, now)
        sessionFile(info.id).write(old ?: emptyDocument())
        sessions[info.id] = info
        activeId = info.id
        writeIndex()
    }

    private fun readSnapshot(info: NativeSessionInfo): NativeSessionSnapshot {
        val value = sessionFile(info.id).read() ?: error("会话内容缺失；原始存储未自动重建")
        val rows = value.optJSONArray("entries") ?: JSONArray()
        val entries = rows.objects().map { row -> ChatEntry(row.getString("id"), row.getString("role"),
            row.getString("text"), row.getLong("at"), row.opt("requestId") as? String) }
        val running = value.optBoolean("running", false)
        return NativeSessionSnapshot(info, entries, value.optJSONArray("history") ?: JSONArray(),
            (value.opt("protocol") as? String)?.let(ModelProtocol::valueOf), running,
            value.optBoolean("mayHaveDeviceEffects", running), value.optString("unresolvedRequestId").takeIf(String::isNotBlank)
                ?: if (value.optBoolean("mayHaveDeviceEffects", running)) entries.lastOrNull { it.role == "user" }?.requestId else null,
            ContextBudgetStatus.fromJson(value.optJSONObject("contextBudget")))
    }

    private fun writeIndex() {
        index.write(JSONObject().put("schema", 1).put("activeId", activeId).put("sessions", JSONArray(
            sessions.values.sortedByDescending { it.updatedAt }.map { info -> JSONObject()
                .put("id", info.id).put("title", info.title).put("createdAt", info.createdAt)
                .put("updatedAt", info.updatedAt).put("archived", info.archived) })))
    }

    private fun sessionFile(id: String): EncryptedStore {
        require(isSessionId(id)) { "Invalid session id" }
        return EncryptedStore(appContext, "native-session-$id")
    }

    private fun titleFrom(value: JSONObject, fallback: String): String {
        val entries = value.optJSONArray("entries") ?: return fallback
        val firstPrompt = entries.objects().firstOrNull { it.optString("role") == "user" }?.optString("text")
            ?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return cleanTitle(firstPrompt.ifBlank { fallback })
    }

    private fun cleanTitle(value: String): String = value.trim().replace(Regex("\\s+"), " ")
        .take(MAX_TITLE_CHARS).ifBlank { "新会话" }

    private fun isSessionId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun emptyDocument() = JSONObject().put("schema", 3).put("running", false)
        .put("mayHaveDeviceEffects", false).put("history", JSONArray()).put("entries", JSONArray())

    companion object {
        const val MAX_SESSIONS = 100
        const val MAX_TITLE_CHARS = 80
        private const val INDEX_NAME = "native-sessions-index"
        private const val LEGACY_NAME = "native-conversation"
    }
}
