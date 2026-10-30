package org.exomind.androbody.nativeapp.body

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.exomind.androbody.nativeapp.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Private Kotlin workspace, not a mounted DSH world. No Shell or arbitrary path capability. */
class Workspace(context: Context) {
    private val root = File(context.filesDir, "native-workspace").apply { mkdirs() }.canonicalFile
    private val lock = Mutex()
    suspend fun read(path: String): JSONObject = lock.withLock {
        val target = resolve(path)
        if (!target.isFile) throw BodyFailure("NOT_FOUND", "工作区内没有这个文件")
        if (target.length() > MAX_BYTES) throw BodyFailure("TOO_LARGE", "请先缩小文件范围")
        val bytes = target.readBytes()
        jsonObject("path" to path, "text" to decodeUtf8(bytes), "bytes" to bytes.size, "sha256" to digest(bytes))
    }
    suspend fun list(): JSONObject = lock.withLock {
        val files = root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter {
            it.isFile && !Files.isSymbolicLink(it.toPath()) && it.extension in setOf("txt", "md", "json", "csv")
        }.take(101).toList()
        jsonObject("files" to JSONArray(files.take(100).map { jsonObject("path" to it.relativeTo(root).invariantSeparatorsPath,
            "bytes" to it.length(), "modifiedAtEpochMs" to it.lastModified()) }), "truncated" to (files.size > 100))
    }
    suspend fun write(path: String, text: String, expectedSha: String? = null): JSONObject = lock.withLock {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw BodyFailure("TOO_LARGE", "内容超过 256 KiB")
        val target = resolve(path)
        if (target.exists()) {
            if (!target.isFile || target.length() > MAX_BYTES || expectedSha == null || digest(target.readBytes()) != expectedSha)
                throw BodyFailure("CONFLICT", "已有文件需要当前 SHA-256；没有覆盖或删除")
        } else if (expectedSha != null) throw BodyFailure("CONFLICT", "预期存在的文件已经缺失")
        target.parentFile?.mkdirs()
        resolve(path) // recheck created parents; app UID is the trusted writing boundary.
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
        val actual = target.readBytes()
        if (!actual.contentEquals(bytes)) throw BodyFailure("VERIFY_FAILED", "写入后读回不一致")
        jsonObject("path" to path, "bytes" to actual.size, "sha256" to digest(actual))
    }
    suspend fun delete(path: String, expectedSha: String): JSONObject = lock.withLock {
        val target = resolve(path)
        if (!target.isFile) throw BodyFailure("NOT_FOUND", "工作区内没有这个文件")
        if (target.length() > MAX_BYTES) throw BodyFailure("TOO_LARGE", "文件超过可删除校验范围")
        val actualSha = digest(target.readBytes())
        if (expectedSha.isBlank() || actualSha != expectedSha)
            throw BodyFailure("CONFLICT", "文件内容已变化；没有删除，请刷新后重新确认")
        AtomicFile(target).delete()
        if (target.exists()) throw BodyFailure("DELETE_UNCONFIRMED", "删除结果未确认；文件仍保留")
        jsonObject("path" to path, "deleted" to true, "previousSha256" to actualSha)
    }
    private fun resolve(path: String): File {
        if (path.isBlank() || path.length > 240 || path.startsWith('/') || path.contains('\\') ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." } || path.any { it.isISOControl() })
            throw BodyFailure("INVALID_PATH", "只接受工作区相对路径")
        if (!isTextExtension(path.substringAfterLast('.', "")))
            throw BodyFailure("UNSUPPORTED_FILE_TYPE", "工作区仅接受 UTF-8 文本：txt、md、json、csv")
        var cursor = root
        for (part in path.split('/')) {
            cursor = File(cursor, part)
            if (Files.isSymbolicLink(cursor.toPath())) throw BodyFailure("SYMLINK_REFUSED", "不跟随符号链接")
        }
        if (!cursor.canonicalPath.startsWith(root.path + File.separator)) throw BodyFailure("OUTSIDE_WORKSPACE", "路径越界")
        return cursor
    }
    companion object {
        const val MAX_BYTES = 256 * 1024
        private fun isTextExtension(extension: String): Boolean = when (extension.lowercase()) {
            "txt", "md", "json", "csv" -> true
            else -> false
        }
        private fun decodeUtf8(bytes: ByteArray): String = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw BodyFailure("INVALID_UTF8", "文件不是有效 UTF-8；原数据未改写") }
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

fun workspaceTools(workspace: Workspace): List<BodyTool> = listOf(
    SimpleTool(Capability(name = "am_workspace_list", title = "查看工作区", description = "List bounded private workspace files; not Android shared storage.", parameters = objectSchema(), effect = Effect.READ_WORKSPACE, gatewayEligible = true)) {
        ToolResult.completed(workspace.list(), "本机私有工作区文件枚举，最多 100 项")
    },
    SimpleTool(Capability(name = "am_workspace_read", title = "读取工作区文件", description = "Read UTF-8 text and its SHA-256 in the private workspace.",
        parameters = objectSchema(jsonObject("path" to textField("Relative workspace path", 240)), listOf("path")), effect = Effect.READ_WORKSPACE, gatewayEligible = true)) {
        ToolResult.completed(workspace.read(it.arguments.stringRequired("path", 240)), "本机文件读回及 SHA-256")
    },
    SimpleTool(Capability(name = "am_workspace_write", title = "保存工作区文件", description = "Write UTF-8 text; existing files require expectedSha256. The native tool policy may require user confirmation.",
        parameters = objectSchema(jsonObject("path" to textField("Relative path", 240), "text" to textField("UTF-8 content", 65536),
            "expectedSha256" to textField("Required for an existing file", 64)), listOf("path", "text")), effect = Effect.WRITE_WORKSPACE, gatewayEligible = false, needsConfirmation = true)) {
        ToolResult.completed(workspace.write(it.arguments.stringRequired("path", 240), it.arguments.stringRequired("text", 65536),
            it.arguments.optString("expectedSha256").takeIf(String::isNotBlank)), "原子文件发布后独立读回；非跨进程事务")
    },
    SimpleTool(Capability(name = "am_workspace_delete", title = "删除工作区文件", description = "Delete one UTF-8 workspace file only when its current SHA-256 matches the supplied expectedSha256. No directory or shared-storage deletion.",
        parameters = objectSchema(jsonObject("path" to textField("Relative workspace path", 240),
            "expectedSha256" to textField("Current file digest required", 64)), listOf("path", "expectedSha256")),
        effect = Effect.WRITE_WORKSPACE, gatewayEligible = false, needsConfirmation = true)) {
        ToolResult.completed(workspace.delete(it.arguments.stringRequired("path", 240),
            it.arguments.stringRequired("expectedSha256", 64)), "通过当前 SHA-256 比对后删除单个工作区文本文件")
    },
)

class SimpleTool(override val capability: Capability, private val action: suspend (Invocation) -> ToolResult) : BodyTool {
    override suspend fun execute(invocation: Invocation) = action(invocation)
}
