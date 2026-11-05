package io.github.leset0ng.hyperwave.ui

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
    fun iosPeakMatchesNativeHyperosAcrossDifferentLayoutSizes() {
        val levels = FloatArray(6) { 1f }
        val ios = FloatArray(24)
        val native = FloatArray(24)
        val reference = FloatArray(24)
        for (nativeSize in floatArrayOf(16f, 20f, 26f)) {
            val iosSize = if (nativeSize <= 16f) 16f else 22f
            for (fallback in booleanArrayOf(false, true)) {
                WaveformGeometry.calculate(false, levels, nativeSize, fallback, true, native)
                WaveformGeometry.calculate(true, levels, iosSize, fallback, true, ios, nativeSize)
                WaveformGeometry.calculate(true, levels, iosSize, fallback, true, reference)
                for (i in 0 until 6) {
                    assertEquals(native[0].toDouble(), ios[i * 4].toDouble(), 0.000002)
                    for (component in 1 until 4) {
                        assertEquals(reference[i * 4 + component].toDouble(),
                                ios[i * 4 + component].toDouble(), 0.0)
                    }
                }
            }
            WaveformGeometry.calculate(true, FloatArray(6), iosSize, false, false, ios, nativeSize)
            for (i in 0 until 6) {
                assertEquals((iosSize * 5f / 48f).toDouble(), ios[i * 4].toDouble(), 0.0)
            }
        }
    }

    @Test
    fun sharedHeightScaleAffectsBothStylesWithoutChangingWidthsOrPause() {
        val levels = floatArrayOf(0.3f, 0.5f, 0.7f, 0.4f, 0.6f, 0.2f)
        val baseline = FloatArray(24)
        val scaled = FloatArray(24)
        for (ios in booleanArrayOf(false, true)) {
            for (fallback in booleanArrayOf(false, true)) {
                for (playing in booleanArrayOf(false, true)) {
                    WaveformGeometry.calculate(ios, levels, 22f, fallback, playing, baseline, 20f)
                    WaveformGeometry.calculate(ios, levels, 22f, fallback, playing, scaled, 20f, 1.5f)
                    for (i in 0 until 24) {
                        val expected = baseline[i] * if (playing && i % 4 == 0) 1.5f else 1f
                        assertEquals(expected.toDouble(), scaled[i].toDouble(), 0.0)
                    }
                }
            }
        }
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
        assertEquals(44.718, output[16].toDouble(), 0.000004)
        assertEquals(output[1].toDouble(), output[17].toDouble(), 0.0)
    }

    @Test
    fun hyperosFallbackAnimatesAllFiveColumns() {
        val output = FloatArray(24)
        WaveformGeometry.calculate(false, floatArrayOf(1f, 1f, 1f, 1f, 1f, 0f), 66f, true, true, output)
        for (i in 0 until 5) assertEquals(44.718, output[i * 4].toDouble(), 0.000004)
        assertEquals(0.0, output[20].toDouble(), 0.0)
    }
}
