package org.exomind.androbody.nativeapp.body

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.exomind.androbody.nativeapp.core.*
import org.exomind.androbody.nativeapp.storage.EncryptedStore
import java.util.UUID

/** SAF is a user-selected external object. Export creates a new document, not an overwrite transaction. */
class DocumentExchange(private val context: Context, private val workspace: Workspace) {
    private val record = EncryptedStore(context, "document-exchange")
    private val mutable = MutableStateFlow("尚无文件交接")
    val state: StateFlow<String> = mutable
    private var pending: ByteArray? = null
    private var writing = false
    private var sequence = 0L
    private var storageFailed = false
    init { runCatching { record.read() }.onSuccess { value ->
        if (value != null) mutable.value = if (value.optString("phase") in setOf("WAITING", "WRITING"))
            "上次导出结果未知，请到所选位置核对；不会重写" else value.optString("detail", "尚无文件交接")
    }.onFailure { storageFailed = true; mutable.value = "文件交接记录不可读；不会自动重试" } }

    suspend fun prepare(path: String): String = withContext(Dispatchers.IO) {
        val text = workspace.read(path).getString("text")
        synchronized(this@DocumentExchange) {
            check(!storageFailed) { "文件交接记录不可读，未覆盖原件" }
            check(pending == null && !writing) { "已有等待或写入中的导出" }
            // Commit the intent before publishing an in-memory snapshot. Failed persistence cannot strand pending.
            record.write(jsonObject("phase" to "WAITING", "detail" to "等待系统选择器"))
            pending = text.toByteArray(Charsets.UTF_8); sequence++
            mutable.value = "等待选择导出位置"
        }
        path.substringAfterLast('/')
    }
    suspend fun export(uri: Uri?) = withContext(Dispatchers.IO) {
        val (generation, bytes) = synchronized(this@DocumentExchange) {
            val captured = pending; pending = null
            if (captured != null) writing = true
            sequence to captured
        }
        if (bytes == null) { mutable.value = "原快照已经丢失或已处理，未重复写入"; return@withContext }
        try {
            if (uri == null) {
                update(generation, "CANCELLED", "导出已取消，原文件保留")
            } else {
                currentCoroutineContext().ensureActive()
                update(generation, "WRITING", "正在写入用户选定的新文档")
                context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes); it.flush() }
                    ?: throw BodyFailure("EXPORT_OPEN", "Provider 未提供写入流")
                val actual = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(bytes.size + 1) }
                if (actual != null && actual.contentEquals(bytes)) update(generation, "COMPLETED", "导出完成，独立读回与 SHA-256 一致：${Workspace.digest(bytes)}")
                else update(generation, "UNKNOWN", "写入流已结束，但未能独立核验；请检查目标文档，勿自动重写")
            }
        } catch (failure: Exception) {
            val detail = "导出中断或失败，目标可能已有部分内容；请人工核对"
            try { update(generation, "UNKNOWN", detail) }
            catch (persistence: Exception) {
                failure.addSuppressed(persistence)
                synchronized(this@DocumentExchange) {
                    storageFailed = true
                    mutable.value = "$detail；记录未能持久化，不得自动重试"
                }
            }
            if (failure is CancellationException) throw failure
        } finally {
            synchronized(this@DocumentExchange) { if (sequence == generation) writing = false }
        }
    }
    suspend fun import(uri: Uri?): String? = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext null
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(Workspace.MAX_BYTES + 1) }
            ?: throw BodyFailure("IMPORT_OPEN", "Provider 未提供读取流")
        if (bytes.size > Workspace.MAX_BYTES) throw BodyFailure("TOO_LARGE", "仅导入 256 KiB 内文本")
        val decoder = Charsets.UTF_8.newDecoder()
        val text = try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            catch (_: Exception) { throw BodyFailure("NOT_UTF8", "当前只接收 UTF-8 文本，没有改写原文件") }
        currentCoroutineContext().ensureActive()
        val path = "imports/${UUID.randomUUID()}.txt"
        workspace.write(path, text)
        path
    }
    @Synchronized private fun update(generation: Long, phase: String, detail: String) {
        if (sequence != generation) return
        record.write(jsonObject("phase" to phase, "detail" to detail)); mutable.value = detail
    }
}
