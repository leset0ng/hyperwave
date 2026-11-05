package ng.lst.hyperwave.ui

import java.util.function.DoubleUnaryOperator

/** Geometry transitions retain directly displayed fallback frames when interrupted. */
internal class GeometryTransition(
        private val liveCurve: DoubleUnaryOperator,
        private val pauseCurve: DoubleUnaryOperator,
) {
    val shown = FloatArray(COMPONENTS)
    private val start = FloatArray(COMPONENTS)
    private val target = FloatArray(COMPONENTS)
    private var started = 0L
    private var duration = 0L
    private var direct = false

    fun reset(geometry: FloatArray) {
        geometry.copyInto(shown, 0, 0, COMPONENTS)
        geometry.copyInto(start, 0, 0, COMPONENTS)
        geometry.copyInto(target, 0, 0, COMPONENTS)
        direct = false
        duration = 0
    }

    fun display(geometry: FloatArray) {
        geometry.copyInto(shown, 0, 0, COMPONENTS)
        direct = true
    }

    fun transition(geometry: FloatArray, now: Long, milliseconds: Long) {
        evaluate(now)
        shown.copyInto(start, 0, 0, COMPONENTS)
        geometry.copyInto(target, 0, 0, COMPONENTS)
        started = now
        duration = milliseconds
        direct = false
    }

    fun evaluate(now: Long) {
        if (direct) return
        val fraction = if (duration == 0L) 1f
                else maxOf(0f, minOf(1f, (now - started) / duration.toFloat()))
        val curve = if (duration == 900L) pauseCurve else liveCurve
        val eased = curve.applyAsDouble(fraction.toDouble()).toFloat()
        for (i in 0 until COMPONENTS) shown[i] = start[i] + (target[i] - start[i]) * eased
    }

    fun animating(now: Long): Boolean = !direct && now - started < duration

    companion object {
        const val COMPONENTS = 24
    }
}
