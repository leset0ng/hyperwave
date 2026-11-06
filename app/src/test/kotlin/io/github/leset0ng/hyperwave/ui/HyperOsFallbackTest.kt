package io.github.leset0ng.hyperwave.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HyperOsFallbackTest {
    @Test
    fun matchesOriginalFloatBitsAcrossCyclesBoundariesAndLargeElapsedTimes() {
        val actualLevels = FloatArray(6)
        val actualStart = FloatArray(5)
        val actualEnd = FloatArray(5)
        val expectedLevels = FloatArray(6)
        val expectedStart = FloatArray(5)
        val expectedEnd = FloatArray(5)

        fun assertAt(elapsedMillis: Long) {
            ReferenceHyperOsFallback.sample(elapsedMillis, expectedLevels, expectedStart, expectedEnd)
            HyperOsFallback.sample(elapsedMillis, actualLevels, actualStart, actualEnd)
            for (i in actualLevels.indices) {
                assertEquals("level[$i] at $elapsedMillis ms",
                        expectedLevels[i].toRawBits(), actualLevels[i].toRawBits())
            }
            for (i in actualStart.indices) {
                assertEquals("gradientStart[$i] at $elapsedMillis ms",
                        expectedStart[i].toRawBits(), actualStart[i].toRawBits())
                assertEquals("gradientEnd[$i] at $elapsedMillis ms",
                        expectedEnd[i].toRawBits(), actualEnd[i].toRawBits())
            }
        }

        // Every millisecond through more than twenty complete cycles, including all segments.
        for (elapsedMillis in 0L..20_000L) assertAt(elapsedMillis)

        // Check both sides of each segment and wrap at increasingly large cycle counts.
        val frames = doubleArrayOf(0.0, 6.0, 13.0, 25.0, 30.729, 36.543, 49.0, 49.99)
        for (cycle in longArrayOf(0L, 1L, 2L, 119L, 10_000L, 1_000_000L, 1_000_000_000L)) {
            for (frame in frames) {
                val boundaryMillis = ((cycle * 49.99 + frame) / 0.06).toLong()
                for (offset in -2L..2L) assertAt(boundaryMillis + offset)
            }
        }
        for (elapsedMillis in longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1,
                -86_400_000L, -1L, 60_000L, 3_600_000L, 86_400_000L,
                Long.MAX_VALUE - 1, Long.MAX_VALUE)) assertAt(elapsedMillis)
        val random = java.util.Random(0x4859504552L)
        repeat(4096) { assertAt(random.nextLong()) }
    }
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

/** Frozen implementation from before identical tracks were shared. */
private object ReferenceHyperOsFallback {
    private val TIMES = arrayOf(
            floatArrayOf(0f, 25f, 49f), floatArrayOf(0f, 13f, 36.543f, 49f),
            floatArrayOf(0f, 25f, 49f), floatArrayOf(0f, 6f, 30.729f, 49f),
            floatArrayOf(0f, 13f, 36.543f, 49f),
    )
    private val HEIGHTS = arrayOf(
            floatArrayOf(10.962f, 44.718f, 10.962f),
            floatArrayOf(28.938f, 10.962f, 44.718f, 30.078f),
            floatArrayOf(44.718f, 10.962f, 44.628f),
            floatArrayOf(19.986f, 10.962f, 44.718f, 21.192f),
            floatArrayOf(28.938f, 10.962f, 44.718f, 30.078f),
    )
    private val GRADIENTS = arrayOf(
            floatArrayOf(1.771f, 6.553f, 1.786f),
            floatArrayOf(3.928f, 1.771f, 6.553f, 4.172f),
            floatArrayOf(6.553f, 1.771f, 6.539f),
            floatArrayOf(2.434f, 1.771f, 6.553f, 2.619f),
            floatArrayOf(3.928f, 1.771f, 6.553f, 4.172f),
    )
    private val HEIGHT_CURVES = arrayOf(
            intArrayOf(0, 0), intArrayOf(1, 0, 2), intArrayOf(0, 2), intArrayOf(1, 0, 2), intArrayOf(1, 0, 2),
    )
    private val CURVES = arrayOf(
            floatArrayOf(0.333f, 0f, 0.667f, 1f),
            floatArrayOf(0.167f, 0.201f, 0.667f, 1f),
            floatArrayOf(0.333f, 0f, 0.833f, 0.799f),
    )

    fun sample(elapsedMillis: Long, levels: FloatArray, gradientStart: FloatArray, gradientEnd: FloatArray) {
        val frame = ((elapsedMillis * 0.06) % 49.99).toFloat()
        for (bar in 0 until 5) {
            val times = TIMES[bar]
            var i = 0
            while (i < times.size - 2 && frame > times[i + 1]) i++
            val fraction = minOf(1f, (frame - times[i]) / (times[i + 1] - times[i]))
            val curve = CURVES[HEIGHT_CURVES[bar][i]]
            val progress = ease(fraction, curve[0], curve[1], curve[2], curve[3])
            val height = HEIGHTS[bar][i] + (HEIGHTS[bar][i + 1] - HEIGHTS[bar][i]) * progress
            levels[bar] = (height - 10.962f) / 33.756f
            val outY = if (i == 0 && (bar == 1 || bar == 4)) 0.576f else if (i == 0 && bar == 3) 0.621f else 0f
            val inY = if (i == times.size - 2) {
                if (bar == 0 || bar == 2) 0.938f else if (bar == 3) 0.651f else 0.498f
            } else 1f
            val gradientProgress = ease(fraction, 0.333f, outY, 0.667f, inY)
            val half = GRADIENTS[bar][i] + (GRADIENTS[bar][i + 1] - GRADIENTS[bar][i]) * gradientProgress
            gradientStart[bar] = -3 * half
            var end = half
            if ((bar == 1 || bar == 4) && i == times.size - 2) end -= 0.001f * gradientProgress
            gradientEnd[bar] = 3 * end
        }
        levels[5] = 0f
    }

    private fun ease(fraction: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        var low = 0f
        var high = 1f
        for (i in 0 until 18) {
            val t = (low + high) / 2
            if (bezier(t, x1, x2) < fraction) low = t else high = t
        }
        return bezier((low + high) / 2, y1, y2)
    }

    private fun bezier(t: Float, first: Float, second: Float): Float {
        val inverse = 1 - t
        return 3 * inverse * inverse * t * first + 3 * inverse * t * t * second + t * t * t
    }
}
