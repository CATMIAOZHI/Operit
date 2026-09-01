package com.ai.assistance.operit.features.reading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegadoAnnotationTrustPolicyTest {
    @Test
    fun `supported Legado package names are trusted`() {
        assertTrue(LegadoAnnotationTrustPolicy.isTrusted("com.legado.app"))
        assertTrue(LegadoAnnotationTrustPolicy.isTrusted("com.legado.app.debug"))
        assertTrue(LegadoAnnotationTrustPolicy.isTrusted("com.legado.app.release"))
    }

    @Test
    fun `unknown package names remain rejected`() {
        assertFalse(LegadoAnnotationTrustPolicy.isTrusted("example.untrusted"))
        assertFalse(LegadoAnnotationTrustPolicy.isTrusted("com.legado.app.fake"))
    }
}
