package com.ai.assistance.operit.data.backup

internal object RawSnapshotPackagePolicy {
    const val OFFICIAL_OPERIT_PACKAGE = "com.ai.assistance.operit"
    const val OPERIT_RY_PACKAGE = "com.rainy.operitry"

    fun requireSourcePackage(actualPackageName: String, expectedPackageName: String) {
        require(actualPackageName == expectedPackageName) {
            "Backup package mismatch: $actualPackageName"
        }
    }

    fun requireArchiveContents(
        actualIncludes: List<String>,
        supportedIncludes: List<List<String>>,
        payloadFileCount: Int
    ) {
        require(actualIncludes in supportedIncludes) { "Invalid backup manifest includes" }
        require(payloadFileCount > 0) { "Invalid backup zip: empty payload" }
    }
}
