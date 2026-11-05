package dev.hyperwave.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformGeometryTest {
    @Test
    fun iosLiveAndFallbackUseTheirDistinctWidthsAndRadii() {
        val levels = floatArrayOf(0.49f, 0.77f, 0.6f, 0.33f, 0.4f, 0f)
        val live = FloatArray(24)
        val fallback = FloatArray(24)
        WaveformGeometry.calculate(true, levels, 66f, false, true, live)
        WaveformGeometry.calculate(true, levels, 66f, true, true, fallback)
        for (i in 0 until 5) {
            assertEquals(live[i * 4 + 1] * 1.2, fallback[i * 4 + 1].toDouble(), 0.000002)
            assertEquals((fallback[i * 4 + 1] / 2).toDouble(), fallback[i * 4 + 2].toDouble(), 0.0)
            assertEquals(live[i * 4 + 3].toDouble(), fallback[i * 4 + 3].toDouble(), 0.0)
        }
        assertEquals(live[20].toDouble(), fallback[20].toDouble(), 0.0)
        assertEquals(live[21].toDouble(), fallback[21].toDouble(), 0.0)
    }

    @Test
    fun hyperosRetainsNativeFiveBarGeometryAndPauseHeight() {
        val output = FloatArray(24)
        WaveformGeometry.calculate(false, FloatArray(6), 66f, false, false, output)
        for (i in 0 until 5) {
            assertEquals(17.4, output[i * 4].toDouble(), 0.000002)
            assertEquals(5.4, output[i * 4 + 1].toDouble(), 0.000001)
            assertEquals(2.7, output[i * 4 + 2].toDouble(), 0.000001)
            assertEquals((9 + 12 * i).toDouble(), output[i * 4 + 3].toDouble(), 0.000001)
        }
        assertEquals(0.0, output[20].toDouble(), 0.0)
        assertEquals(0.0, output[21].toDouble(), 0.0)
    }

    @Test
    fun hyperosWidthStaysFixedAndTheHighestBandIsNotDropped() {
        val levels = floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f)
        val output = FloatArray(24)
        WaveformGeometry.calculate(false, levels, 66f, false, true, output)
        assertEquals(10.962, output[0].toDouble(), 0.000002)
        assertEquals(66.0, output[16].toDouble(), 0.000004)
        assertEquals(output[1].toDouble(), output[17].toDouble(), 0.0)
    }

    @Test
    fun hyperosFallbackAnimatesAllFiveColumns() {
        val output = FloatArray(24)
        WaveformGeometry.calculate(false, floatArrayOf(1f, 1f, 1f, 1f, 1f, 0f), 66f, true, true, output)
        for (i in 0 until 5) assertEquals(66.0, output[i * 4].toDouble(), 0.000004)
        assertEquals(0.0, output[20].toDouble(), 0.0)
    }
}
