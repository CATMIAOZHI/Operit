package com.ai.assistance.operit.api.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFileTranscriberTest {
    private fun resample(from: Int, input: FloatArray): FloatArray {
        val out = ArrayList<Float>()
        val r = AudioFileTranscriber.Resampler(from)
        input.forEach { x -> r.push(x) { out += it } }
        return out.toFloatArray()
    }

    @Test fun sixteenKilohertzPassesThroughUnchanged() {
        val input = FloatArray(100) { it / 100f }
        assertEquals(input.toList(), resample(16000, input).toList())
    }

    @Test fun fortyEightKilohertzKeepsEveryThirdSample() {
        val input = FloatArray(4800) { (it % 7) / 7f }
        val out = resample(48000, input)
        assertEquals(1600, out.size)
        out.forEachIndexed { i, x -> assertEquals(input[i * 3], x, 1e-6f) }
    }

    @Test fun eightKilohertzFillsTheGapsHalfway() {
        val out = resample(8000, floatArrayOf(0f, 1f, 0f))
        assertEquals(listOf(0f, 0.5f, 1f, 0.5f, 0f), out.toList())
    }
}