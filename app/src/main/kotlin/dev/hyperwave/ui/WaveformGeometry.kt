package dev.hyperwave.ui

/** Target geometry in physical pixels: height, width, radius, horizontal center. */
internal object WaveformGeometry {
    fun calculate(ios: Boolean, levels: FloatArray, size: Float, fallback: Boolean,
                  playing: Boolean, output: FloatArray) {
        val base = size * 5f / 48f
        val slot = base * 1.12f
        val pitch = slot + (size - 6 * slot) / 5
        for (i in 0 until 6) {
            val offset = i * 4
            if (ios) {
                val value = maxOf(0f, minOf(1f, levels[i]))
                val canned = fallback && i < 5
                val width = (if (canned) size / 8 else base) * (1 + 0.12f * value)
                output[offset] = maxOf(base, value * size)
                output[offset + 1] = width
                output[offset + 2] = if (canned) width / 2 else Math.round(width / 2).toFloat()
                output[offset + 3] = i * pitch + slot / 2
            } else {
                // Resample all six DSP bands onto the five native visual columns.
                val position = i * 5f / 4
                val left = minOf(5, position.toInt())
                val right = minOf(5, left + 1)
                val level = if (fallback) levels[minOf(i, 4)]
                        else levels[left] + (levels[right] - levels[left]) * (position - left)
                val value = maxOf(0f, minOf(1f, level))
                output[offset] = if (i == 5) 0f else size / 66 * (if (playing) 10.962f + 55.038f * value else 17.4f)
                output[offset + 1] = if (i == 5) 0f else size * 5.4f / 66
                output[offset + 2] = output[offset + 1] / 2
                output[offset + 3] = size * (9 + 12 * minOf(i, 4)) / 66
            }
        }
    }
}
