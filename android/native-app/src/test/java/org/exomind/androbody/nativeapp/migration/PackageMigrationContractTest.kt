package org.exomind.androbody.nativeapp.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageMigrationContractTest {
    @Test fun matchingVerificationAndDebugPackagesResolveOnlyTheirCounterpart() {
        assertEquals("org.exomind.andromorph.verification",
            MigrationArchive.expectedLegacyPackage("org.exomind.androbody.verification"))
        assertEquals("org.exomind.andromorph.debug",
            MigrationArchive.expectedLegacyPackage("org.exomind.androbody.debug"))
        assertEquals("org.exomind.andromorph", MigrationArchive.expectedLegacyPackage("org.exomind.androbody"))
        assertNull(MigrationArchive.expectedLegacyPackage("org.exomind.androbody.preview"))
    }

    @Test fun sourceProviderAcceptsOnlyMatchingTargetSuffix() {
        assertEquals("org.exomind.androbody.verification",
            MigrationArchive.expectedTargetForSource("org.exomind.andromorph.verification"))
        assertEquals("org.exomind.androbody", MigrationArchive.expectedTargetForSource("org.exomind.andromorph"))
        assertNull(MigrationArchive.expectedTargetForSource("org.exomind.andromorph.preview"))
    }

    @Test fun archivePathContractRejectsTraversalAndUnregisteredData() {
        MigrationArchive.validateArchiveEntryName("stores/native-session-01234567-89ab-cdef-0123-456789abcdef.json")
        MigrationArchive.validateArchiveEntryName("workspace/sensors/sample.json")
        MigrationArchive.validateArchiveEntryName("recordings/01234567-89ab-cdef-0123-456789abcdef.m4a")
        MigrationArchive.validateArchiveEntryName("diagnostics/runtime-console/trace-0.jsonl")
        assertThrows(PackageMigrationFailure::class.java) {
            MigrationArchive.validateArchiveEntryName("workspace/../../shared_prefs/secrets.xml")
        }
        assertThrows(PackageMigrationFailure::class.java) {
            MigrationArchive.validateArchiveEntryName("files/Android/data/another.app/private.db")
        }
        assertThrows(PackageMigrationFailure::class.java) {
            MigrationArchive.validateArchiveEntryName("stores/../../keystore.json")
        }
    }
}
