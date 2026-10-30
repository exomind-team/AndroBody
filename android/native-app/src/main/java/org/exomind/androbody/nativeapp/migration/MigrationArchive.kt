package org.exomind.androbody.nativeapp.migration

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.exomind.androbody.nativeapp.AppGraph
import org.exomind.androbody.nativeapp.body.AudioRecordingStore
import org.exomind.androbody.nativeapp.storage.EncryptedStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class LegacyApplication(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
)

data class PackageMigrationSummary(
    val sessions: Int,
    val encryptedRecords: Int,
    val workspaceFiles: Int,
    val recordings: Int,
    val diagnosticFiles: Int,
)

class PackageMigrationFailure(val code: String, cause: Throwable? = null) : Exception(code, cause)

/** Local, signature-gated archive transfer. No archive or body enters app logs. */
internal object MigrationArchive {
    const val AUTHORITY = "org.exomind.androbody.migration"
    const val SIGNATURE_PERMISSION = "org.exomind.androbody.permission.MIGRATION_EXPORT"
    const val MANIFEST_NAME = "migration-manifest.json"
    const val SCHEMA_VERSION = 1
    const val MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    private const val MIGRATION_STATE_PREFS = "androbody-package-migration"
    private const val MIGRATION_STATE_KEY = "decision"
    private const val MIGRATION_MARKER = "package-migration-v1.in-progress"
    private const val ROOT_SENTINEL = ".androbody-migration-v1"
    private const val STAGING_DIRECTORY = "package-migration-v1"
    private const val PREFERENCES_NAME = "native-preferences-v1"
    private const val OVERLAY_PREFERENCES_NAME = "native-overlay-placement"
    private val storeNamePattern = Regex("[a-z0-9][a-z0-9-]{0,79}")
    private val sessionIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private data class ExportEntry(val path: String, val bytes: ByteArray? = null, val file: File? = null) {
        fun length(): Long = bytes?.size?.toLong() ?: file?.length() ?: error("Empty migration entry")
    }

    private data class ObservedEntry(val path: String, val byteCount: Long, val sha256: String)

    fun expectedLegacyPackage(applicationId: String): String? {
        if (!applicationId.startsWith("org.exomind.androbody")) return null
        val suffix = applicationId.removePrefix("org.exomind.androbody")
        if (suffix.isNotEmpty() && !suffix.matches(Regex("\\.(debug|verification)"))) return null
        return "org.exomind.andromorph$suffix"
    }

    fun expectedTargetForSource(sourcePackage: String): String? = targetPackageFromLegacy(sourcePackage)

    fun export(context: Context, graph: AppGraph, output: OutputStream) {
        val nativeState = runBlocking {
            withTimeout(30_000L) { graph.native.state.first { !it.initializing } }
        }
        if (nativeState.error != null) throw PackageMigrationFailure("SOURCE_SESSION_UNAVAILABLE")
        if (nativeState.running || graph.gateway.state.value.activeRequests > 0 ||
            graph.approvals.pending.value.isNotEmpty() ||
            graph.audioRecordings.items.value.any { it.status == AudioRecordingStore.STATUS_RECORDING }) {
            throw PackageMigrationFailure("SOURCE_BUSY")
        }

        val entries = collectExportEntries(context)
        val totalBytes = entries.sumOf(ExportEntry::length)
        if (totalBytes > MAX_ARCHIVE_BYTES) throw PackageMigrationFailure("SOURCE_DATA_TOO_LARGE")

        val recordRows = JSONArray()
        val summary = summarize(entries)
        val zip = ZipOutputStream(BufferedOutputStream(output))
        run {
            for (entry in entries) {
                val observed = writeEntry(zip, entry)
                recordRows.put(JSONObject().put("path", observed.path)
                    .put("byteCount", observed.byteCount).put("sha256", observed.sha256))
            }
            val manifest = JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("sourcePackage", context.packageName)
                .put("targetPackage", targetPackageFromLegacy(context.packageName)
                    ?: throw PackageMigrationFailure("SOURCE_IDENTITY_INVALID"))
                .put("createdAtEpochMs", System.currentTimeMillis())
                .put("summary", JSONObject().put("sessions", summary.sessions)
                    .put("encryptedRecords", summary.encryptedRecords)
                    .put("workspaceFiles", summary.workspaceFiles)
                    .put("recordings", summary.recordings)
                    .put("diagnosticFiles", summary.diagnosticFiles))
                .put("entries", recordRows)
            zip.putNextEntry(ZipEntry(MANIFEST_NAME).apply { time = 0L })
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
            zip.flush()
        }
    }

    fun import(context: Context, source: LegacyApplication): PackageMigrationSummary {
        recoverInterruptedImport(context)
        requireTargetIsFresh(context)
        val stageRoot = File(context.cacheDir, STAGING_DIRECTORY)
        deleteOwnedTree(stageRoot)
        val incoming = File(stageRoot, "incoming")
        if (!incoming.mkdirs()) throw PackageMigrationFailure("STAGING_CREATE_FAILED")
        var markerWritten = false
        var transactionId: String? = null
        try {
            val uri = android.net.Uri.parse("content://${MigrationArchive.AUTHORITY}/snapshot")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw PackageMigrationFailure("SOURCE_STREAM_UNAVAILABLE")
            val observed = readArchive(input, incoming)
            val manifestFile = File(incoming, MANIFEST_NAME)
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            validateManifest(context, source, manifest, observed)
            val summary = readSummary(manifest)
            val stagedRoots = prepareImportedData(context, incoming, manifest, summary)

            transactionId = writeMigrationMarker(context, source.packageName)
            markerWritten = true
            writeRootSentinels(context, stagedRoots, transactionId)
            commitStagedRoots(context, stagedRoots)
            applyPreferences(context, incoming)
            verifyCommittedData(context, incoming, manifest, summary)

            val decision = context.getSharedPreferences(MIGRATION_STATE_PREFS, Context.MODE_PRIVATE)
            if (!decision.edit().putString(MIGRATION_STATE_KEY, "imported")
                    .putString("summary", summary.toJson().toString()).commit())
                throw PackageMigrationFailure("MIGRATION_DECISION_NOT_SAVED")
            cleanupRootSentinels(context, transactionId)
            runCatching { deleteMigrationMarker(context) }
            markerWritten = false
            deleteOwnedTree(stageRoot)
            return summary
        } catch (failure: PackageMigrationFailure) {
            val rollbackOk = if (markerWritten) rollbackCommittedData(context) else true
            deleteOwnedTree(stageRoot)
            if (!rollbackOk) throw PackageMigrationFailure("MIGRATION_ROLLBACK_FAILED", failure)
            throw failure
        } catch (failure: Exception) {
            val rollbackOk = if (markerWritten) rollbackCommittedData(context) else true
            deleteOwnedTree(stageRoot)
            throw PackageMigrationFailure(if (rollbackOk) "MIGRATION_VALIDATION_OR_WRITE_FAILED" else "MIGRATION_ROLLBACK_FAILED", failure)
        }
    }

    fun recoverInterruptedImport(context: Context) {
        val stageRoot = File(context.cacheDir, STAGING_DIRECTORY)
        if (!File(context.noBackupFilesDir, MIGRATION_MARKER).exists()) {
            deleteOwnedTree(stageRoot)
            return
        }
        val decision = context.getSharedPreferences(MIGRATION_STATE_PREFS, Context.MODE_PRIVATE)
            .getString(MIGRATION_STATE_KEY, null)
        val transactionId = readMigrationTransactionId(context)
        if (decision == "imported" || decision == "acknowledged") {
            cleanupRootSentinels(context, transactionId)
            deleteMigrationMarker(context)
            deleteOwnedTree(stageRoot)
            return
        }
        if (!rollbackCommittedData(context)) throw PackageMigrationFailure("MIGRATION_ROLLBACK_FAILED")
        deleteOwnedTree(stageRoot)
    }

    fun decisionMade(context: Context): Boolean = context.getSharedPreferences(MIGRATION_STATE_PREFS, Context.MODE_PRIVATE)
        .getString(MIGRATION_STATE_KEY, null) in setOf("imported", "skipped", "acknowledged")

    fun pendingCompletion(context: Context): PackageMigrationSummary? {
        val prefs = context.getSharedPreferences(MIGRATION_STATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(MIGRATION_STATE_KEY, null) != "imported") return null
        val json = prefs.getString("summary", null) ?: return null
        return runCatching { fromSummaryJson(JSONObject(json)) }.getOrNull()
    }

    fun acknowledgeCompletion(context: Context) {
        val prefs = context.getSharedPreferences(MIGRATION_STATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(MIGRATION_STATE_KEY, null) != "imported") return
        if (!prefs.edit().putString(MIGRATION_STATE_KEY, "acknowledged").remove("summary").commit())
            throw PackageMigrationFailure("MIGRATION_DECISION_NOT_SAVED")
    }

    fun skip(context: Context) {
        if (!context.getSharedPreferences(MIGRATION_STATE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(MIGRATION_STATE_KEY, "skipped").commit()) {
            throw PackageMigrationFailure("MIGRATION_DECISION_NOT_SAVED")
        }
    }

    private fun collectExportEntries(context: Context): List<ExportEntry> {
        val entries = mutableListOf<ExportEntry>()
        val nativeRoot = File(context.filesDir, "native-v1")
        if (nativeRoot.exists()) {
            val storeNames = nativeRoot.listFiles().orEmpty().mapNotNull { file ->
                when {
                    file.name.endsWith(".bin.bak") -> file.name.removeSuffix(".bin.bak")
                    file.name.endsWith(".bin") -> file.name.removeSuffix(".bin")
                    else -> null
                }
            }.distinct().sorted()
            for (name in storeNames) {
                if (!storeNamePattern.matches(name)) throw PackageMigrationFailure("SOURCE_STORE_NAME_INVALID")
                val value = EncryptedStore(context, name).read()
                    ?: throw PackageMigrationFailure("SOURCE_STORE_UNREADABLE")
                val bytes = value.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > EncryptedStore.MAX_RECORD_BYTES) throw PackageMigrationFailure("SOURCE_STORE_TOO_LARGE")
                entries += ExportEntry("stores/$name.json", bytes = bytes)
            }
        }
        entries += ExportEntry("preferences/$PREFERENCES_NAME.json", bytes = encodePreferences(context, PREFERENCES_NAME))
        entries += ExportEntry("preferences/$OVERLAY_PREFERENCES_NAME.json", bytes = encodePreferences(context, OVERLAY_PREFERENCES_NAME))
        collectDirectoryFiles(File(context.filesDir, "native-workspace"), "workspace", entries)
        collectDirectoryFiles(File(context.filesDir, "recordings"), "recordings", entries)
        collectDiagnosticFiles(File(nativeRoot, "runtime-console"), "diagnostics/runtime-console", entries)
        collectDiagnosticFiles(File(nativeRoot, "network-diagnostics"), "diagnostics/network-diagnostics", entries)
        val paths = entries.map(ExportEntry::path)
        if (paths.size != paths.toSet().size) throw PackageMigrationFailure("SOURCE_ENTRY_DUPLICATE")
        return entries.sortedBy(ExportEntry::path)
    }

    private fun collectDirectoryFiles(root: File, prefix: String, entries: MutableList<ExportEntry>) {
        if (!root.exists()) return
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory)
            throw PackageMigrationFailure("SOURCE_DIRECTORY_INVALID")
        fun visit(directory: File) {
            val children = directory.listFiles() ?: throw PackageMigrationFailure("SOURCE_DIRECTORY_UNREADABLE")
            for (child in children.sortedBy(File::getName)) {
                if (Files.isSymbolicLink(child.toPath())) throw PackageMigrationFailure("SOURCE_SYMLINK_REFUSED")
                if (child.isDirectory) visit(child)
                else if (child.isFile) {
                    val relative = child.relativeTo(root).invariantSeparatorsPath
                    validateRelativePath(relative)
                    entries += ExportEntry("$prefix/$relative", file = child)
                } else throw PackageMigrationFailure("SOURCE_FILE_KIND_UNSUPPORTED")
            }
        }
        visit(root)
    }

    private fun collectDiagnosticFiles(root: File, prefix: String, entries: MutableList<ExportEntry>) {
        if (!root.exists()) return
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory)
            throw PackageMigrationFailure("SOURCE_DIAGNOSTIC_DIRECTORY_INVALID")
        val files = root.listFiles() ?: throw PackageMigrationFailure("SOURCE_DIAGNOSTIC_DIRECTORY_UNREADABLE")
        for (file in files.sortedBy(File::getName)) {
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile || !file.name.matches(Regex("trace-[0-9]+\\.jsonl")))
                throw PackageMigrationFailure("SOURCE_DIAGNOSTIC_FILE_INVALID")
            entries += ExportEntry("$prefix/${file.name}", file = file)
        }
    }

    private fun encodePreferences(context: Context, name: String): ByteArray {
        val values = JSONObject()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().forEach { (key, value) ->
            validatePreferenceKey(key)
            val encoded = when (value) {
                is String -> JSONObject().put("type", "string").put("value", value)
                is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is Long -> JSONObject().put("type", "long").put("value", value)
                is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                is Set<*> -> JSONObject().put("type", "stringSet").put("value", JSONArray(value.map {
                    it as? String ?: throw PackageMigrationFailure("SOURCE_PREFERENCE_TYPE_UNSUPPORTED")
                }.sorted()))
                else -> throw PackageMigrationFailure("SOURCE_PREFERENCE_TYPE_UNSUPPORTED")
            }
            values.put(key, encoded)
        }
        return values.toString().toByteArray(Charsets.UTF_8)
    }

    private fun writeEntry(zip: ZipOutputStream, source: ExportEntry): ObservedEntry {
        val declaredLength = source.length()
        if (declaredLength < 0 || declaredLength > MAX_ENTRY_BYTES) throw PackageMigrationFailure("SOURCE_ENTRY_TOO_LARGE")
        zip.putNextEntry(ZipEntry(source.path).apply { time = 0L })
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        fun writeChunk(bytes: ByteArray, length: Int) {
            count += length
            if (count > MAX_ENTRY_BYTES) throw PackageMigrationFailure("SOURCE_ENTRY_TOO_LARGE")
            digest.update(bytes, 0, length)
            zip.write(bytes, 0, length)
        }
        source.bytes?.let { writeChunk(it, it.size) }
        source.file?.let { file ->
            BufferedInputStream(FileInputStream(file)).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    writeChunk(buffer, length)
                }
            }
        }
        if (count != declaredLength) throw PackageMigrationFailure("SOURCE_CHANGED_DURING_EXPORT")
        zip.closeEntry()
        return ObservedEntry(source.path, count, digest.digest().toHex())
    }

    private fun readArchive(input: InputStream, incoming: File): List<ObservedEntry> {
        val observed = mutableListOf<ObservedEntry>()
        var totalBytes = 0L
        var manifestSeen = false
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (manifestSeen || entry.isDirectory) throw PackageMigrationFailure("ARCHIVE_ENTRY_ORDER_INVALID")
                if (entry.name == MANIFEST_NAME) {
                    val bytes = readBounded(zip, 1_048_576L)
                    File(incoming, MANIFEST_NAME).writeBytes(bytes)
                    manifestSeen = true
                    zip.closeEntry()
                    continue
                }
                validateArchiveEntryName(entry.name)
                val target = safeChild(incoming, entry.name)
                if (target.exists()) throw PackageMigrationFailure("ARCHIVE_ENTRY_DUPLICATE")
                target.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var entryBytes = 0L
                BufferedOutputStream(FileOutputStream(target)).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val length = zip.read(buffer)
                        if (length < 0) break
                        entryBytes += length
                        totalBytes += length
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_ARCHIVE_BYTES)
                            throw PackageMigrationFailure("ARCHIVE_TOO_LARGE")
                        digest.update(buffer, 0, length)
                        output.write(buffer, 0, length)
                    }
                }
                observed += ObservedEntry(entry.name, entryBytes, digest.digest().toHex())
                zip.closeEntry()
            }
        }
        if (!manifestSeen) throw PackageMigrationFailure("ARCHIVE_MANIFEST_MISSING")
        return observed
    }

    private fun validateManifest(context: Context, source: LegacyApplication, manifest: JSONObject, observed: List<ObservedEntry>) {
        if (manifest.optInt("schemaVersion") != SCHEMA_VERSION) throw PackageMigrationFailure("ARCHIVE_SCHEMA_UNSUPPORTED")
        if (manifest.optString("sourcePackage") != source.packageName ||
            manifest.optString("targetPackage") != context.packageName) throw PackageMigrationFailure("ARCHIVE_IDENTITY_MISMATCH")
        val expected = manifest.optJSONArray("entries") ?: throw PackageMigrationFailure("ARCHIVE_MANIFEST_INVALID")
        val expectedRows = linkedMapOf<String, Pair<Long, String>>()
        for (index in 0 until expected.length()) {
            val row = expected.optJSONObject(index) ?: throw PackageMigrationFailure("ARCHIVE_MANIFEST_INVALID")
            val path = row.optString("path")
            validateArchiveEntryName(path)
            if (expectedRows.put(path, row.optLong("byteCount") to row.optString("sha256")) != null)
                throw PackageMigrationFailure("ARCHIVE_MANIFEST_DUPLICATE")
        }
        if (expectedRows.size != observed.size) throw PackageMigrationFailure("ARCHIVE_ENTRY_COUNT_MISMATCH")
        for (actual in observed) {
            val expectedRow = expectedRows[actual.path] ?: throw PackageMigrationFailure("ARCHIVE_ENTRY_UNLISTED")
            if (expectedRow.first != actual.byteCount || expectedRow.second != actual.sha256)
                throw PackageMigrationFailure("ARCHIVE_ENTRY_DIGEST_MISMATCH")
        }
        val summary = readSummary(manifest)
        if (summary.encryptedRecords != observed.count { it.path.startsWith("stores/") } ||
            summary.workspaceFiles != observed.count { it.path.startsWith("workspace/") } ||
            summary.recordings != observed.count { it.path.startsWith("recordings/") && it.path.endsWith(".json") } ||
            summary.diagnosticFiles != observed.count { it.path.startsWith("diagnostics/") })
            throw PackageMigrationFailure("ARCHIVE_SUMMARY_MISMATCH")
    }

    private fun prepareImportedData(context: Context, incoming: File, manifest: JSONObject, summary: PackageMigrationSummary): List<String> {
        val prepared = File(context.cacheDir, "$STAGING_DIRECTORY/prepared")
        deleteOwnedTree(prepared)
        val encryptedRoot = File(prepared, "native-v1")
        if (!encryptedRoot.mkdirs()) throw PackageMigrationFailure("STAGING_CREATE_FAILED")
        val stores = linkedMapOf<String, JSONObject>()
        for (sourceFile in File(incoming, "stores").listFiles().orEmpty().sortedBy(File::getName)) {
            if (!sourceFile.isFile || !sourceFile.name.endsWith(".json")) throw PackageMigrationFailure("ARCHIVE_STORE_ENTRY_INVALID")
            val name = sourceFile.name.removeSuffix(".json")
            if (!storeNamePattern.matches(name)) throw PackageMigrationFailure("ARCHIVE_STORE_NAME_INVALID")
            val json = JSONObject(sourceFile.readText(Charsets.UTF_8))
            if (sourceFile.length() > EncryptedStore.MAX_RECORD_BYTES) throw PackageMigrationFailure("ARCHIVE_STORE_TOO_LARGE")
            EncryptedStore(context, name, encryptedRoot).write(json)
            val readback = EncryptedStore(context, name, encryptedRoot).read()
                ?: throw PackageMigrationFailure("STAGED_STORE_READBACK_FAILED")
            if (readback.toString() != json.toString()) throw PackageMigrationFailure("STAGED_STORE_READBACK_MISMATCH")
            stores[name] = json
        }
        validateSessionIndex(stores, summary.sessions)
        val workspaceIncoming = File(incoming, "workspace")
        if (workspaceIncoming.exists()) copyTree(workspaceIncoming, File(prepared, "native-workspace"))
        val recordingsIncoming = File(incoming, "recordings")
        if (recordingsIncoming.exists()) copyTree(recordingsIncoming, File(prepared, "recordings"))
        val runtimeLogs = File(incoming, "diagnostics/runtime-console")
        if (runtimeLogs.exists()) copyTree(runtimeLogs, File(encryptedRoot, "runtime-console"))
        val legacyLogs = File(incoming, "diagnostics/network-diagnostics")
        if (legacyLogs.exists()) copyTree(legacyLogs, File(encryptedRoot, "network-diagnostics"))
        validatePreferences(File(incoming, "preferences/$PREFERENCES_NAME.json"))
        validatePreferences(File(incoming, "preferences/$OVERLAY_PREFERENCES_NAME.json"))
        validateRecordings(File(prepared, "recordings"))
        val stagedDirectories = listOf("native-v1", "native-workspace", "recordings")
            .filter { File(prepared, it).exists() }
        if (stagedDirectories.isEmpty()) throw PackageMigrationFailure("ARCHIVE_HAS_NO_USER_DATA")
        return stagedDirectories
    }

    private fun validateSessionIndex(stores: Map<String, JSONObject>, expectedCount: Int) {
        val index = stores["native-sessions-index"] ?: run {
            if (expectedCount != 0) throw PackageMigrationFailure("SESSION_INDEX_MISSING")
            return
        }
        val rows = index.optJSONArray("sessions") ?: throw PackageMigrationFailure("SESSION_INDEX_INVALID")
        if (rows.length() != expectedCount || rows.length() > 100) throw PackageMigrationFailure("SESSION_COUNT_MISMATCH")
        for (i in 0 until rows.length()) {
            val id = rows.getJSONObject(i).optString("id")
            if (!sessionIdPattern.matches(id) || stores["native-session-$id"] == null)
                throw PackageMigrationFailure("SESSION_RECORD_MISSING")
        }
    }

    private fun validatePreferences(file: File) {
        if (!file.isFile) throw PackageMigrationFailure("PREFERENCE_RECORD_MISSING")
        val values = JSONObject(file.readText(Charsets.UTF_8))
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            validatePreferenceKey(key)
            val row = values.optJSONObject(key) ?: throw PackageMigrationFailure("PREFERENCE_RECORD_INVALID")
            when (row.optString("type")) {
                "string" -> row.optString("value")
                "boolean" -> if (row.opt("value") !is Boolean) throw PackageMigrationFailure("PREFERENCE_RECORD_INVALID")
                "int", "long", "float" -> if (row.opt("value") !is Number) throw PackageMigrationFailure("PREFERENCE_RECORD_INVALID")
                "stringSet" -> if (row.optJSONArray("value") == null) throw PackageMigrationFailure("PREFERENCE_RECORD_INVALID")
                else -> throw PackageMigrationFailure("PREFERENCE_RECORD_INVALID")
            }
        }
    }

    private fun validateRecordings(root: File) {
        if (!root.exists()) return
        val metadataFiles = root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        for (file in metadataFiles) {
            val metadata = JSONObject(file.readText(Charsets.UTF_8))
            val id = metadata.optString("artifactId")
            if (!sessionIdPattern.matches(id) || file.name != "$id.json") throw PackageMigrationFailure("RECORDING_METADATA_INVALID")
            val relative = metadata.optString("relativePath").removePrefix("recordings/")
            if (relative.isBlank() || relative.contains("/") || relative.contains("\\") || relative.contains(".."))
                throw PackageMigrationFailure("RECORDING_PATH_INVALID")
            val media = File(root, relative)
            if (!media.isFile || media.length() != metadata.optLong("bytes"))
                throw PackageMigrationFailure("RECORDING_FILE_MISSING_OR_CHANGED")
            val expectedSha = metadata.optString("sha256").takeIf { it.isNotBlank() && it != "null" }
            if (expectedSha != null && sha256(media) != expectedSha) throw PackageMigrationFailure("RECORDING_DIGEST_MISMATCH")
        }
    }

    private fun applyPreferences(context: Context, incoming: File) {
        writePreferences(context, PREFERENCES_NAME, File(incoming, "preferences/$PREFERENCES_NAME.json"))
        writePreferences(context, OVERLAY_PREFERENCES_NAME, File(incoming, "preferences/$OVERLAY_PREFERENCES_NAME.json"))
    }

    private fun writePreferences(context: Context, name: String, source: File) {
        val values = JSONObject(source.readText(Charsets.UTF_8))
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val row = values.getJSONObject(key)
            when (row.getString("type")) {
                "string" -> editor.putString(key, row.getString("value"))
                "boolean" -> editor.putBoolean(key, row.getBoolean("value"))
                "int" -> editor.putInt(key, row.getInt("value"))
                "long" -> editor.putLong(key, row.getLong("value"))
                "float" -> editor.putFloat(key, row.getDouble("value").toFloat())
                "stringSet" -> editor.putStringSet(key, row.getJSONArray("value").toStringSet())
                else -> throw PackageMigrationFailure("PREFERENCE_RECORD_INVALID")
            }
        }
        if (!editor.commit()) throw PackageMigrationFailure("PREFERENCE_WRITE_FAILED")
    }

    private fun verifyCommittedData(context: Context, incoming: File, manifest: JSONObject, summary: PackageMigrationSummary) {
        val expectedEntries = manifest.getJSONArray("entries")
        for (index in 0 until expectedEntries.length()) {
            val row = expectedEntries.getJSONObject(index)
            val path = row.getString("path")
            if (path.startsWith("stores/")) {
                val name = path.removePrefix("stores/").removeSuffix(".json")
                val source = JSONObject(File(incoming, path).readText(Charsets.UTF_8))
                val readback = EncryptedStore(context, name).read() ?: throw PackageMigrationFailure("STORE_READBACK_FAILED")
                if (source.toString() != readback.toString()) throw PackageMigrationFailure("STORE_READBACK_MISMATCH")
            } else if (path.startsWith("preferences/")) {
                val name = path.removePrefix("preferences/").removeSuffix(".json")
                val expected = JSONObject(File(incoming, path).readText(Charsets.UTF_8))
                val actual = JSONObject(String(encodePreferences(context, name), Charsets.UTF_8))
                if (expected.toString() != actual.toString()) throw PackageMigrationFailure("PREFERENCE_READBACK_MISMATCH")
            } else {
                val destination = when {
                    path.startsWith("workspace/") -> File(context.filesDir, "native-workspace/${path.removePrefix("workspace/")}")
                    path.startsWith("recordings/") -> File(context.filesDir, "recordings/${path.removePrefix("recordings/")}")
                    path.startsWith("diagnostics/runtime-console/") -> File(context.filesDir, "native-v1/runtime-console/${path.removePrefix("diagnostics/runtime-console/")}")
                    path.startsWith("diagnostics/network-diagnostics/") -> File(context.filesDir, "native-v1/network-diagnostics/${path.removePrefix("diagnostics/network-diagnostics/")}")
                    else -> throw PackageMigrationFailure("ARCHIVE_PATH_UNSUPPORTED")
                }
                if (!destination.isFile || destination.length() != row.getLong("byteCount") || sha256(destination) != row.getString("sha256"))
                    throw PackageMigrationFailure("FILE_READBACK_MISMATCH")
            }
        }
        validateSessionIndex(loadCommittedStores(context), summary.sessions)
    }

    private fun loadCommittedStores(context: Context): Map<String, JSONObject> {
        val root = File(context.filesDir, "native-v1")
        return root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bin") }.associate { file ->
            val name = file.name.removeSuffix(".bin")
            name to (EncryptedStore(context, name).read() ?: throw PackageMigrationFailure("STORE_READBACK_FAILED"))
        }
    }

    private fun commitStagedRoots(context: Context, roots: List<String>) {
        val prepared = File(context.cacheDir, "$STAGING_DIRECTORY/prepared")
        for (name in roots) {
            val source = File(prepared, name)
            val destination = File(context.filesDir, name)
            if (destination.exists()) throw PackageMigrationFailure("TARGET_DATA_CHANGED_DURING_MIGRATION")
            destination.parentFile?.mkdirs()
            try {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                if (!source.renameTo(destination)) throw PackageMigrationFailure("MIGRATION_COMMIT_FAILED")
            }
        }
    }

    private fun validateManifestSummary(manifest: JSONObject): PackageMigrationSummary {
        val summary = manifest.optJSONObject("summary") ?: throw PackageMigrationFailure("ARCHIVE_SUMMARY_INVALID")
        return PackageMigrationSummary(
            sessions = summary.optInt("sessions", -1),
            encryptedRecords = summary.optInt("encryptedRecords", -1),
            workspaceFiles = summary.optInt("workspaceFiles", -1),
            recordings = summary.optInt("recordings", -1),
            diagnosticFiles = summary.optInt("diagnosticFiles", -1),
        ).also { if (listOf(it.sessions, it.encryptedRecords, it.workspaceFiles, it.recordings, it.diagnosticFiles).any { count -> count < 0 })
            throw PackageMigrationFailure("ARCHIVE_SUMMARY_INVALID") }
    }

    private fun readSummary(manifest: JSONObject): PackageMigrationSummary = validateManifestSummary(manifest)

    private fun summarize(entries: List<ExportEntry>): PackageMigrationSummary {
        val index = entries.firstOrNull { it.path == "stores/native-sessions-index.json" }
            ?.bytes?.let { JSONObject(String(it, Charsets.UTF_8)).optJSONArray("sessions")?.length() } ?: 0
        return PackageMigrationSummary(
            sessions = index,
            encryptedRecords = entries.count { it.path.startsWith("stores/") },
            workspaceFiles = entries.count { it.path.startsWith("workspace/") },
            recordings = entries.count { it.path.startsWith("recordings/") && it.path.endsWith(".json") },
            diagnosticFiles = entries.count { it.path.startsWith("diagnostics/") },
        )
    }

    private fun writeMigrationMarker(context: Context, sourcePackage: String): String {
        val transactionId = UUID.randomUUID().toString()
        val marker = File(context.noBackupFilesDir, MIGRATION_MARKER)
        val atomic = AtomicFile(marker)
        val output = atomic.startWrite()
        try {
            output.write(JSONObject().put("state", "importing").put("sourcePackage", sourcePackage)
                .put("transactionId", transactionId).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw PackageMigrationFailure("MIGRATION_MARKER_WRITE_FAILED", failure)
        }
        return transactionId
    }

    private fun deleteMigrationMarker(context: Context) {
        val marker = File(context.noBackupFilesDir, MIGRATION_MARKER)
        if (marker.exists() && !marker.delete()) throw PackageMigrationFailure("MIGRATION_MARKER_CLEANUP_FAILED")
    }

    private fun rollbackCommittedData(context: Context): Boolean {
        var ok = true
        val transactionId = readMigrationTransactionId(context)
        for (name in listOf("native-v1", "native-workspace", "recordings")) {
            val target = File(context.filesDir, name)
            if (!target.exists()) continue
            val sentinel = File(target, ROOT_SENTINEL)
            if (transactionId == null || !sentinel.isFile || sentinel.readText(Charsets.UTF_8) != transactionId ||
                !deleteOwnedTree(target)) ok = false
        }
        for (name in listOf(PREFERENCES_NAME, OVERLAY_PREFERENCES_NAME)) {
            if (!context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()) ok = false
        }
        if (ok) runCatching { deleteMigrationMarker(context) }.onFailure { ok = false }
        return ok
    }

    private fun requireTargetIsFresh(context: Context) {
        for (name in listOf("native-v1", "native-workspace", "recordings")) {
            val directory = File(context.filesDir, name)
            if (directory.exists())
                throw PackageMigrationFailure("TARGET_DATA_NOT_EMPTY")
        }
        if (context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).all.isNotEmpty() ||
            context.getSharedPreferences(OVERLAY_PREFERENCES_NAME, Context.MODE_PRIVATE).all.isNotEmpty())
            throw PackageMigrationFailure("TARGET_PREFERENCES_NOT_EMPTY")
    }

    private fun writeRootSentinels(context: Context, roots: List<String>, transactionId: String?) {
        if (transactionId.isNullOrBlank()) throw PackageMigrationFailure("MIGRATION_TRANSACTION_MISSING")
        val prepared = File(context.cacheDir, "$STAGING_DIRECTORY/prepared")
        for (name in roots) {
            val root = File(prepared, name)
            if (!root.exists() && !root.mkdirs()) throw PackageMigrationFailure("STAGING_CREATE_FAILED")
            File(root, ROOT_SENTINEL).writeText(transactionId, Charsets.UTF_8)
        }
    }

    private fun cleanupRootSentinels(context: Context, transactionId: String?) {
        if (transactionId.isNullOrBlank()) return
        for (name in listOf("native-v1", "native-workspace", "recordings")) {
            val sentinel = File(File(context.filesDir, name), ROOT_SENTINEL)
            if (sentinel.isFile && sentinel.readText(Charsets.UTF_8) == transactionId) sentinel.delete()
        }
    }

    private fun readMigrationTransactionId(context: Context): String? = runCatching {
        JSONObject(File(context.noBackupFilesDir, MIGRATION_MARKER).readText(Charsets.UTF_8))
            .optString("transactionId").takeIf(String::isNotBlank)
    }.getOrNull()

    private fun deleteOwnedTree(root: File): Boolean {
        if (!root.exists()) return true
        if (Files.isSymbolicLink(root.toPath())) return false
        return root.deleteRecursively()
    }

    private fun copyTree(source: File, destination: File) {
        if (!source.exists()) return
        if (Files.isSymbolicLink(source.toPath()) || !source.isDirectory) throw PackageMigrationFailure("ARCHIVE_DIRECTORY_INVALID")
        fun visit(from: File, to: File) {
            val children = from.listFiles() ?: throw PackageMigrationFailure("STAGING_READ_FAILED")
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) throw PackageMigrationFailure("ARCHIVE_SYMLINK_REFUSED")
                val target = File(to, child.name)
                if (child.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) throw PackageMigrationFailure("STAGING_CREATE_FAILED")
                    visit(child, target)
                } else {
                    target.parentFile?.mkdirs()
                    Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        if (!destination.exists() && !destination.mkdirs()) throw PackageMigrationFailure("STAGING_CREATE_FAILED")
        visit(source, destination)
    }

    private fun safeChild(root: File, relative: String): File {
        validateArchiveEntryName(relative)
        val target = File(root, relative.replace('/', File.separatorChar))
        val rootPath = root.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(rootPath)) throw PackageMigrationFailure("ARCHIVE_PATH_OUTSIDE_ROOT")
        return target
    }

    internal fun validateArchiveEntryName(path: String) {
        if (path.isBlank() || path.length > 512 || path.startsWith('/') || path.contains('\\') ||
            path.any(Char::isISOControl) || path.split('/').any { it.isBlank() || it == "." || it == ".." })
            throw PackageMigrationFailure("ARCHIVE_PATH_INVALID")
        when {
            path.startsWith("stores/") -> if (!path.removePrefix("stores/").removeSuffix(".json").matches(storeNamePattern) || !path.endsWith(".json"))
                throw PackageMigrationFailure("ARCHIVE_STORE_PATH_INVALID")
            path == "preferences/$PREFERENCES_NAME.json" || path == "preferences/$OVERLAY_PREFERENCES_NAME.json" -> Unit
            path.startsWith("workspace/") -> {
                val relative = path.removePrefix("workspace/")
                if (relative.substringAfterLast('.', "").lowercase() !in setOf("txt", "md", "json", "csv"))
                    throw PackageMigrationFailure("ARCHIVE_WORKSPACE_PATH_INVALID")
                validateRelativePath(relative)
            }
            path.startsWith("recordings/") -> {
                val name = path.removePrefix("recordings/")
                if (!name.matches(Regex("[0-9a-f-]{36}(\\.json|\\.m4a|\\.partial\\.m4a)")))
                    throw PackageMigrationFailure("ARCHIVE_RECORDING_PATH_INVALID")
            }
            path.startsWith("diagnostics/runtime-console/") || path.startsWith("diagnostics/network-diagnostics/") -> {
                if (!path.substringAfterLast('/').matches(Regex("trace-[0-9]+\\.jsonl")))
                    throw PackageMigrationFailure("ARCHIVE_DIAGNOSTIC_PATH_INVALID")
            }
            else -> throw PackageMigrationFailure("ARCHIVE_PATH_UNSUPPORTED")
        }
    }

    private fun validateRelativePath(path: String) {
        if (path.isBlank() || path.length > 240 || path.startsWith('/') || path.contains('\\') ||
            path.any(Char::isISOControl) || path.split('/').any { it.isBlank() || it == "." || it == ".." })
            throw PackageMigrationFailure("ARCHIVE_RELATIVE_PATH_INVALID")
    }

    private fun validatePreferenceKey(key: String) {
        if (key.isBlank() || key.length > 256 || key.any(Char::isISOControl))
            throw PackageMigrationFailure("PREFERENCE_KEY_INVALID")
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw PackageMigrationFailure("ARCHIVE_MANIFEST_TOO_LARGE")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun JSONArray.toStringSet(): Set<String> = (0 until length()).map { getString(it) }.toSet()

    private fun targetPackageFromLegacy(sourcePackage: String): String? {
        if (!sourcePackage.startsWith("org.exomind.andromorph")) return null
        val suffix = sourcePackage.removePrefix("org.exomind.andromorph")
        if (suffix.isNotEmpty() && !suffix.matches(Regex("\\.(debug|verification)"))) return null
        return "org.exomind.androbody$suffix"
    }

    private fun PackageMigrationSummary.toJson(): JSONObject = JSONObject()
        .put("sessions", sessions).put("encryptedRecords", encryptedRecords)
        .put("workspaceFiles", workspaceFiles).put("recordings", recordings)
        .put("diagnosticFiles", diagnosticFiles)

    private fun fromSummaryJson(value: JSONObject) = PackageMigrationSummary(
        value.getInt("sessions"), value.getInt("encryptedRecords"), value.getInt("workspaceFiles"),
        value.getInt("recordings"), value.getInt("diagnosticFiles"))
}
