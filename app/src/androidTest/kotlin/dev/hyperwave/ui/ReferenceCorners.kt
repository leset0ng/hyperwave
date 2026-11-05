package dev.hyperwave.ui

import android.graphics.Path
import android.graphics.RectF

/** Original per-vertex interpolation, with the measured atlas shared by both versions. */
internal class ReferenceCorners {
    private val aspects = ContinuousCorners.ASPECTS
    private val radii = ContinuousCorners.RADII
    private val edges = ContinuousCorners.EDGES
    private val path = Path()

    fun build(bounds: RectF, radius: Float): Path {
        path.rewind()
        val width = bounds.width()
        val height = bounds.height()
        if (!(width > 0) || !(height > 0) || !width.isFinite() || !height.isFinite()) return path
        if (!(radius > 0) || !radius.isFinite()) {
            path.addRect(bounds, Path.Direction.CW)
            return path
        }
        val aspect = height / width
        val relativeRadius = radius / width
        val ai = lower(aspects, aspect)
        val ri = lower(radii, relativeRadius)
        val at = fraction(aspects[ai], aspects[ai + 1], aspect)
        val rt = fraction(radii[ri], radii[ri + 1], relativeRadius)
        val cap = minOf(1f, aspect / 2)
        val left = bounds.left
        val right = bounds.right
        val top = bounds.top
        val bottom = bounds.bottom
        path.moveTo(left + width / 2, top)
        for (i in 1..STEPS) {
            val y = cap * i / STEPS
            path.lineTo(left + width * edge(ai, ri, at, rt, y), top + width * y)
        }
        path.lineTo(left + width * edge(ai, ri, at, rt, cap), bottom - width * cap)
        for (i in STEPS - 1 downTo 0) {
            val y = cap * i / STEPS
            path.lineTo(left + width * edge(ai, ri, at, rt, y), bottom - width * y)
        }
        for (i in 1..STEPS) {
            val y = cap * i / STEPS
            path.lineTo(right - width * edge(ai, ri, at, rt, y), bottom - width * y)
        }
        path.lineTo(right - width * edge(ai, ri, at, rt, cap), top + width * cap)
        for (i in STEPS - 1 downTo 0) {
            val y = cap * i / STEPS
            path.lineTo(right - width * edge(ai, ri, at, rt, y), top + width * y)
        }
        path.close()
        return path
    }

    private fun edge(ai: Int, ri: Int, at: Float, rt: Float, y: Float): Float {
        val position = maxOf(0f, minOf(STEPS.toFloat(), y * STEPS))
        val index = minOf(STEPS - 1, position.toInt())
        val yt = position - index
        val a = sample(edges[ai][ri], index, yt)
        val b = sample(edges[ai][ri + 1], index, yt)
        val c = sample(edges[ai + 1][ri], index, yt)
        val d = sample(edges[ai + 1][ri + 1], index, yt)
        return (a + (b - a) * rt) * (1 - at) + (c + (d - c) * rt) * at
    }

    private fun sample(values: FloatArray, index: Int, fraction: Float): Float =
            values[index] + (values[index + 1] - values[index]) * fraction

    private fun lower(grid: FloatArray, value: Float): Int {
        var index = 0
        while (index < grid.size - 2 && value > grid[index + 1]) index++
        return index
    }

    private fun fraction(low: Float, high: Float, value: Float): Float =
            maxOf(0f, minOf(1f, (value - low) / (high - low)))

    private companion object {
        const val STEPS = 48
    }
}
