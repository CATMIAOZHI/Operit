package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsdToCnyRateValidationTest {

    @Test
    fun `double must remain finite and positive after Float conversion`() {
        assertNull(usdToCnyStorageValue(1e-50))
        assertNull(usdToCnyStorageValue(1e50))
        assertNull(usdToCnyStorageValue(Double.NaN))
        assertNull(usdToCnyStorageValue(Double.POSITIVE_INFINITY))
        assertNull(usdToCnyStorageValue(Double.NEGATIVE_INFINITY))
        assertEquals(7.35f, usdToCnyStorageValue(7.35))
    }

    @Test
    fun `historical invalid Float values are treated as missing`() {
        assertNull(validStoredUsdToCnyRate(0f))
        assertNull(validStoredUsdToCnyRate(-1f))
        assertNull(validStoredUsdToCnyRate(Float.NaN))
        assertNull(validStoredUsdToCnyRate(Float.POSITIVE_INFINITY))
        assertEquals(7.25, validStoredUsdToCnyRate(7.25f)!!, 0.0)
        assertTrue(validStoredUsdToCnyRate(Float.MIN_VALUE)!! > 0.0)

        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f).forEach { stored ->
            assertEquals(7.2, resolveUsdToCnyExchangeRate(stored), 0.0)
            assertEquals(7.0 to true, resolveUsdToCnyRateWithEstimate(stored))
        }
        assertEquals(7.25, resolveUsdToCnyExchangeRate(7.25f), 0.0)
        assertEquals(7.25 to false, resolveUsdToCnyRateWithEstimate(7.25f))
    }
}
