package io.github.leset0ng.hyperwave.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HyperOsFallbackTest {
    @Test
    fun startsAtNativeHeightsAndIndependentGradientEndpoints() {
        val levels = FloatArray(6)
        val start = FloatArray(5)
        val end = FloatArray(5)
        HyperOsFallback.sample(0L, levels, start, end)
        val geometry = FloatArray(24)
        WaveformGeometry.calculate(false, levels, 66f, true, true, geometry)
        val expected = floatArrayOf(10.962f, 28.938f, 44.718f, 19.986f, 28.938f)
        val halfGradients = floatArrayOf(5.313f, 11.784f, 19.659f, 7.302f, 11.784f)
        for (i in 0 until 5) {
            assertEquals(expected[i].toDouble(), geometry[i * 4].toDouble(), 0.0001)
            assertEquals(-halfGradients[i].toDouble(), start[i].toDouble(), 0.0001)
            assertEquals(halfGradients[i].toDouble(), end[i].toDouble(), 0.0001)
        }
        assertEquals(0.0, levels[5].toDouble(), 0.0)
    }

    @Test
    fun nativeTracksDifferFromIosDurationAndPermutation() {
        val levels = FloatArray(6)
        val start = FloatArray(5)
        val end = FloatArray(5)
        HyperOsFallback.sample(417L, levels, start, end)
        assertEquals(1.0, levels[0].toDouble(), 0.0001)
        assertEquals(0.0, levels[2].toDouble(), 0.0001)
        assertEquals(levels[1].toDouble(), levels[4].toDouble(), 0.0)
        assertEquals(-start[0].toDouble(), end[0].toDouble(), 0.000001)
    }
}
