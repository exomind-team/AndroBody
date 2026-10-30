package org.exomind.androbody.nativeapp.migration

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.exomind.androbody.nativeapp.BuildConfig

/** Resolves only the matching legacy identity and starts the explicitly user-triggered local transfer. */
object PackageDataMigration {
    @Suppress("DEPRECATION")
    fun installedLegacySource(context: Context): LegacyApplication? {
        val legacyPackage = MigrationArchive.expectedLegacyPackage(BuildConfig.APPLICATION_ID) ?: return null
        val provider = context.packageManager.resolveContentProvider(MigrationArchive.AUTHORITY, 0) ?: return null
        if (provider.packageName != legacyPackage) return null
        val packageInfo = try {
            context.packageManager.getPackageInfo(legacyPackage, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode
            else packageInfo.versionCode.toLong()
        return LegacyApplication(legacyPackage, packageInfo.versionName.orEmpty(), code)
    }

    fun decisionMade(context: Context): Boolean = MigrationArchive.decisionMade(context)

    fun pendingCompletion(context: Context): PackageMigrationSummary? = MigrationArchive.pendingCompletion(context)

    fun recoverInterruptedImport(context: Context) = MigrationArchive.recoverInterruptedImport(context)

    fun import(context: Context, source: LegacyApplication): PackageMigrationSummary =
        MigrationArchive.import(context, source)

    fun skip(context: Context) = MigrationArchive.skip(context)

    fun acknowledgeCompletion(context: Context) = MigrationArchive.acknowledgeCompletion(context)
}
