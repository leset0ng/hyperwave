package dev.hyperwave.ui

/**
 * Recovered BouncyBars trajectories and default Core Animation scalar cubic.
 * Hermite tangents were checked against public macOS QuartzCore presentation values.
 */
internal object FallbackFrames {
    private val TIMES = arrayOf(
            floatArrayOf(0f, 0.091f, 0.1818f, 0.273f, 0.3636f, 0.4545f, 0.5454f, 0.6363f, 0.7272f, 0.8181f, 0.909f, 1f),
            floatArrayOf(0f, 0.091f, 0.1818f, 0.2727f, 0.3636f, 0.4545f, 0.5454f, 0.6363f, 0.7272f, 0.8181f, 0.909f, 1f),
    )
    private val HEIGHTS = arrayOf(
            floatArrayOf(0.49f, 0.51f, 0.429f, 0.789f, 0.655f, 0.31f, 0.473f, 0.51f, 0.532f, 0.688f, 0.709f, 0.49f),
            floatArrayOf(0.77f, 0.48f, 0.77f, 0.552f, 0.451f, 0.461f, 0.881f, 0.7f, 0.907f, 0.534f, 0.519f, 0.77f),
            floatArrayOf(0.6f, 0.68f, 0.58f, 0.829f, 0.68f, 0.79f, 0.614f, 0.88f, 0.571f, 0.829f, 0.62f, 0.6f),
            floatArrayOf(0.33f, 0.589f, 0.68f, 0.469f, 0.937f, 0.599f, 0.717f, 0.52f, 0.688f, 0.44f, 0.73f, 0.33f),
            floatArrayOf(0.4f, 0.24f, 0.489f, 0.57f, 0.421f, 0.339f, 0.25f, 0.72f, 0.29f, 0.588f, 0.501f, 0.4f),
    )
    private val VARIANTS = arrayOf(
            intArrayOf(0, 1, 2, 3, 4), intArrayOf(4, 2, 0, 1, 3), intArrayOf(3, 0, 1, 2, 4),
    )

    fun sample(cycles: Double, variant: Int, output: FloatArray) {
        val phase = (cycles - Math.floor(cycles)).toFloat()
        for (band in 0 until 5) {
            val track = VARIANTS[variant][band]
            val times = TIMES[if (track == 0) 0 else 1]
            val values = HEIGHTS[track]
            var i = 0
            while (i < times.size - 2 && phase > times[i + 1]) i++
            val dt = times[i + 1] - times[i]
            val t = (phase - times[i]) / dt
            val t2 = t * t
            val t3 = t2 * t
            output[band] = (2 * t3 - 3 * t2 + 1) * values[i] +
                    (t3 - 2 * t2 + t) * dt * tangent(times, values, i) +
                    (-2 * t3 + 3 * t2) * values[i + 1] +
                    (t3 - t2) * dt * tangent(times, values, i + 1)
        }
        output[5] = 0f
    }

    private fun tangent(times: FloatArray, values: FloatArray, index: Int): Float {
        val left = maxOf(0, index - 1)
        val right = minOf(values.size - 1, index + 1)
        return (values[right] - values[left]) / (times[right] - times[left])
    }
}
