package ng.lst.hyperwave.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackFramesTest {
    @Test
    fun matchesQuartzCorePresentationSamples() {
        val phases = floatArrayOf(0f, 0.0455f, 0.091f, 0.1364f, 0.1818f, 0.2274f, 0.273f, 0.9545f, 1f)
        val reference = doubleArrayOf(49.0, 50.6316694169417, 51.0, 44.829251834524115, 42.9,
                61.2304207014108, 78.9, 61.44931968114346, 49.0)
        val output = FloatArray(6)
        for (i in phases.indices) {
            FallbackFrames.sample(phases[i].toDouble(), 0, output)
            assertEquals("phase=" + phases[i], reference[i] / 100, output[0].toDouble(), 0.000002)
            assertEquals(0.0, output[5].toDouble(), 0.0)
        }
    }

    @Test
    fun variantsPermuteTheRecoveredTracks() {
        val expected = arrayOf(
                floatArrayOf(0.49f, 0.77f, 0.6f, 0.33f, 0.4f, 0f),
                floatArrayOf(0.4f, 0.6f, 0.49f, 0.77f, 0.33f, 0f),
                floatArrayOf(0.33f, 0.49f, 0.77f, 0.6f, 0.4f, 0f),
        )
        val output = FloatArray(6)
        for (variant in 0 until 3) {
            FallbackFrames.sample(0.0, variant, output)
            assertArrayEquals(expected[variant], output, 0.000001f)
        }
    }

    @Test
    fun loopsWithoutChangingEndpointTangents() {
        val before = FloatArray(6)
        val after = FloatArray(6)
        FallbackFrames.sample(0.001, 0, before)
        FallbackFrames.sample(1.001, 0, after)
        assertArrayEquals(before, after, 0.000001f)
        assertEquals(0.4902258155101536, before[0].toDouble(), 0.000001)
        FallbackFrames.sample(0.999, 0, before)
        assertEquals(0.4924209186681869, before[0].toDouble(), 0.000001)
    }
}
