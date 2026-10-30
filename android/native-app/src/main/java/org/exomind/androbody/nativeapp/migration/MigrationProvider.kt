package org.exomind.androbody.nativeapp.migration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import org.exomind.androbody.nativeapp.MorphApplication
import java.util.concurrent.Executors

/** Source-side, read-only pipe. Manifest signature permission and caller identity are checked before opening it. */
class MigrationProvider : ContentProvider() {
    override fun onCreate(): Boolean = context != null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val appContext = context ?: throw IllegalStateException("MIGRATION_PROVIDER_CONTEXT_MISSING")
        if (mode != "r" || uri.authority != MigrationArchive.AUTHORITY || uri.path != "/snapshot")
            throw IllegalArgumentException("MIGRATION_URI_UNSUPPORTED")
        if (appContext.checkCallingPermission(MigrationArchive.SIGNATURE_PERMISSION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("MIGRATION_SIGNATURE_PERMISSION_REQUIRED")
        }
        val caller = callingPackage ?: throw SecurityException("MIGRATION_CALLER_UNKNOWN")
        val expectedTarget = MigrationArchive.expectedTargetForSource(appContext.packageName)
            ?: throw SecurityException("MIGRATION_SOURCE_IDENTITY_INVALID")
        if (caller != expectedTarget || appContext.packageManager.checkSignatures(appContext.packageName, caller) !=
            android.content.pm.PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("MIGRATION_CALLER_NOT_AUTHORIZED")
        }

        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        EXPORT_EXECUTOR.execute {
            try {
                val app = appContext.applicationContext as MorphApplication
                val graph = runBlocking { app.awaitGraph() }
                val output = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
                MigrationArchive.export(appContext, graph, output)
                output.close()
            } catch (failure: Exception) {
                runCatching {
                    writeSide.closeWithError((failure as? PackageMigrationFailure)?.code ?: "SOURCE_EXPORT_FAILED")
                }
            }
        }
        return readSide
    }

    override fun getType(uri: Uri): String = "application/zip"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("MIGRATION_PROVIDER_READ_ONLY")

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("MIGRATION_PROVIDER_READ_ONLY")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("MIGRATION_PROVIDER_READ_ONLY")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("MIGRATION_PROVIDER_READ_ONLY")

    companion object {
        private val EXPORT_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "androbody-migration-export").apply { isDaemon = true }
        }
    }
}
