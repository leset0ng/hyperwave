package dev.hyperwave.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Random

class ContinuousCornersTest {
    @Test
    fun samplesPreserveOriginalInterpolationAcrossAtlasBoundaries() {
        val aspects = floatArrayOf(0.25f, 1f, 1.125f, 1.25f, 1.375f, 1.5f, 1.625f, 1.75f, 2f, 4f, 10f, 20f)
        val radii = floatArrayOf(0.1f, 0.4f, 0.45f, 0.5f, 0.55f, 0.6f, 1f)
        val widths = floatArrayOf(1f, 3.75f, 5.4f, 9.9f)
        val samples = FloatArray(STEPS + 2)
        for (width in widths) {
            for (aspect in aspects) {
                for (radius in radii) verify(width, width * aspect, width * radius, samples)
            }
        }
    }

    @Test
    fun reusedSamplesPreserveOriginalCoordinatesForAnimatedGeometry() {
        val random = Random(139)
        val samples = FloatArray(STEPS + 2)
        for (i in 0 until 1000) {
            val width = 1 + random.nextFloat() * 20
            val height = 0.1f + random.nextFloat() * 200
            val radius = 0.01f + random.nextFloat() * width
            verify(width, height, radius, samples)
        }
    }

    @Test
    fun directCapIsPreservedWhenSampleStepRoundsDifferently() {
        val width = 1f
        var height = 0.8000001f
        var cap = minOf(1f, height / width / 2)
        // Search adjacent representable heights to exercise the separate join sample.
        while (cap * STEPS / STEPS == cap) {
            height = Math.nextUp(height)
            cap = minOf(1f, height / width / 2)
        }
        assertNotEquals(cap.toBits(), (cap * STEPS / STEPS).toBits())
        verify(width, height, 0.5f, FloatArray(STEPS + 2))
    }

    private fun verify(width: Float, height: Float, radius: Float, samples: FloatArray) {
        val cap = ContinuousCorners.sampleEdges(width, height, radius, samples)
        assertBits(minOf(1f, height / width / 2), cap)
        for (i in 0..STEPS) {
            val y = cap * i / STEPS
            val original = originalEdge(width, height, radius, y)
            assertBits(original, samples[i])
            // Check both mirrored x coordinates at a nonzero origin as used by the path builder.
            assertBits(7.25f + width * original, 7.25f + width * samples[i])
            assertBits(7.25f + width - width * original, 7.25f + width - width * samples[i])
        }
        assertBits(originalEdge(width, height, radius, cap), samples[STEPS + 1])
    }

    // Independent copy of the original per-vertex interpolation, using the unchanged measured atlas.
    private fun originalEdge(width: Float, height: Float, radius: Float, y: Float): Float {
        val aspects = ContinuousCorners.ASPECTS
        val radii = ContinuousCorners.RADII
        val edges = ContinuousCorners.EDGES
        val aspect = height / width
        val relativeRadius = radius / width
        var ai = 0
        while (ai < aspects.size - 2 && aspect > aspects[ai + 1]) ai++
        var ri = 0
        while (ri < radii.size - 2 && relativeRadius > radii[ri + 1]) ri++
        val at = maxOf(0f, minOf(1f, (aspect - aspects[ai]) / (aspects[ai + 1] - aspects[ai])))
        val rt = maxOf(0f, minOf(1f, (relativeRadius - radii[ri]) / (radii[ri + 1] - radii[ri])))
        val position = maxOf(0f, minOf(STEPS.toFloat(), y * STEPS))
        val index = minOf(STEPS - 1, position.toInt())
        val yt = position - index
        val a = interpolate(edges[ai][ri], index, yt)
        val b = interpolate(edges[ai][ri + 1], index, yt)
        val c = interpolate(edges[ai + 1][ri], index, yt)
        val d = interpolate(edges[ai + 1][ri + 1], index, yt)
        return (a + (b - a) * rt) * (1 - at) + (c + (d - c) * rt) * at
    }

    private fun interpolate(values: FloatArray, index: Int, fraction: Float): Float =
            values[index] + (values[index + 1] - values[index]) * fraction

    private fun assertBits(expected: Float, actual: Float) {
        assertEquals(expected.toBits(), actual.toBits())
    }

    private companion object {
        const val STEPS = 48
    }
}
