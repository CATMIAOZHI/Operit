package com.ai.assistance.operit.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawSnapshotPackagePolicyTest {
    @Test
    fun officialSnapshotIsAcceptedForMigration() {
        RawSnapshotPackagePolicy.requireSourcePackage(
            actualPackageName = RawSnapshotPackagePolicy.OFFICIAL_OPERIT_PACKAGE,
            expectedPackageName = RawSnapshotPackagePolicy.OFFICIAL_OPERIT_PACKAGE
        )
    }

    @Test
    fun rySnapshotIsRejectedForOfficialMigration() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RawSnapshotPackagePolicy.requireSourcePackage(
                actualPackageName = RawSnapshotPackagePolicy.OPERIT_RY_PACKAGE,
                expectedPackageName = RawSnapshotPackagePolicy.OFFICIAL_OPERIT_PACKAGE
            )
        }

        assertEquals(
            "Backup package mismatch: ${RawSnapshotPackagePolicy.OPERIT_RY_PACKAGE}",
            error.message
        )
    }

    @Test
    fun officialSnapshotIsRejectedForNormalRyRestore() {
        assertThrows(IllegalArgumentException::class.java) {
            RawSnapshotPackagePolicy.requireSourcePackage(
                actualPackageName = RawSnapshotPackagePolicy.OFFICIAL_OPERIT_PACKAGE,
                expectedPackageName = RawSnapshotPackagePolicy.OPERIT_RY_PACKAGE
            )
        }
    }

    @Test
    fun emptyPayloadIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RawSnapshotPackagePolicy.requireArchiveContents(
                actualIncludes = listOf("payload/files/"),
                supportedIncludes = listOf(listOf("payload/files/")),
                payloadFileCount = 0
            )
        }
    }

    @Test
    fun alteredIncludesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RawSnapshotPackagePolicy.requireArchiveContents(
                actualIncludes = listOf("payload/databases/"),
                supportedIncludes = listOf(listOf("payload/files/", "payload/databases/")),
                payloadFileCount = 1
            )
        }
    }

    @Test
    fun legacyManifestWithoutExternalFilesIsAccepted() {
        val current = listOf("payload/files/", "payload/external_files/", "payload/databases/")
        val legacy = current - "payload/external_files/"

        RawSnapshotPackagePolicy.requireArchiveContents(
            actualIncludes = legacy,
            supportedIncludes = listOf(current, legacy),
            payloadFileCount = 1
        )
    }
}
