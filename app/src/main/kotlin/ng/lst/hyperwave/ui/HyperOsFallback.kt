package ng.lst.hyperwave.ui

/** Scalar motion and gradient endpoints recovered from the native 66x66 music_wave. */
internal object HyperOsFallback {
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
