package com.ai.assistance.operit.features.reading

internal object LegadoAnnotationTrustPolicy {
    fun isTrusted(packageName: String): Boolean = packageName in TRUSTED_PACKAGES

    private val TRUSTED_PACKAGES =
        setOf(
            "com.legado.app",
            "com.legado.app.debug",
            "com.legado.app.release",
        )
}
