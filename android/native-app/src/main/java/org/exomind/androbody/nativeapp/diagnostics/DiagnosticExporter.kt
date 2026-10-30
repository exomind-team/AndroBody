package org.exomind.androbody.nativeapp.diagnostics

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.exomind.androbody.nativeapp.core.readAtMost

/** Written and independently read back are different outcomes. No automatic write replay. */
data class DiagnosticExportObservation(val status: String, val uri: String, val bytes: Int, val sha256: String,
    val atMillis: Long, val failureClass: String? = null)

suspend fun exportDiagnostics(resolver: ContentResolver, uri: Uri, store: DiagnosticStore, includeContent: Boolean): DiagnosticExportObservation =
    withContext(Dispatchers.IO) {
        writeAndReadBack(resolver, uri, store.export(includeContent).toString(2).toByteArray(Charsets.UTF_8), store)
    }

suspend fun exportRuntimeDiagnostics(
    resolver: ContentResolver,
    uri: Uri,
    store: DiagnosticStore,
    filter: RuntimeLogFilter,
): DiagnosticExportObservation = withContext(Dispatchers.IO) {
    writeAndReadBack(resolver, uri, store.exportRuntime(filter).toString(2).toByteArray(Charsets.UTF_8), store)
}

private fun writeAndReadBack(resolver: ContentResolver, uri: Uri, payload: ByteArray, store: DiagnosticStore): DiagnosticExportObservation {
    require(payload.size <= 8 * 1024 * 1024) { "Diagnostic export budget exceeded" }
    val digest = sha256(payload)
    var written = false
    val observation = try {
        val output = resolver.openOutputStream(uri, "wt") ?: error("Output unavailable")
        output.use { it.write(payload); it.flush() }; written = true
        val readback = resolver.openInputStream(uri)?.use { it.readAtMost(payload.size + 1) }
        DiagnosticExportObservation(if (readback != null && readback.contentEquals(payload)) "VERIFIED" else "WRITTEN_UNVERIFIED",
            uri.toString(), payload.size, digest, System.currentTimeMillis())
    } catch (failure: Exception) {
        DiagnosticExportObservation(if (written) "WRITTEN_UNVERIFIED" else "WRITE_OUTCOME_UNKNOWN", uri.toString(),
            payload.size, digest, System.currentTimeMillis(), failure.javaClass.name)
    }
    store.recordExport(observation)
    return observation
}
