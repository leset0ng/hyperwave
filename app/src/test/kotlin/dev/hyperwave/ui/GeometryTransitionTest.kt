package dev.hyperwave.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.DoubleUnaryOperator

class GeometryTransitionTest {
    private val motion = GeometryTransition(DoubleUnaryOperator { it }, DoubleUnaryOperator { it })

    private fun geometry(value: Float): FloatArray = FloatArray(GeometryTransition.COMPONENTS) { value }

    @Test
    fun fallbackToPauseStartsAtTheDisplayedFrame() {
        motion.reset(geometry(2f))
        motion.display(geometry(15f))
        motion.transition(geometry(3f), 5000L, 900L)
        motion.evaluate(5000L)
        assertArrayEquals(geometry(15f), motion.shown, 0f)
        motion.evaluate(5450L)
        assertArrayEquals(geometry(9f), motion.shown, 0.000001f)
        motion.evaluate(5900L)
        assertArrayEquals(geometry(3f), motion.shown, 0f)
    }

    @Test
    fun fallbackToLiveDoesNotReuseAnOldAnimationTarget() {
        motion.reset(geometry(2f))
        motion.transition(geometry(8f), 1000L, 80L)
        motion.display(geometry(17f))
        motion.transition(geometry(5f), 2000L, 80L)
        motion.evaluate(2000L)
        assertArrayEquals(geometry(17f), motion.shown, 0f)
        motion.evaluate(2080L)
        assertArrayEquals(geometry(5f), motion.shown, 0f)
    }

    @Test
    fun liveUpdatesResumeTheCurrentPresentation() {
        motion.reset(geometry(2f))
        motion.transition(geometry(10f), 1000L, 80L)
        motion.transition(geometry(14f), 1040L, 80L)
        motion.evaluate(1040L)
        assertArrayEquals(geometry(6f), motion.shown, 0f)
        assertTrue(motion.animating(1040L))
        motion.evaluate(1120L)
        assertArrayEquals(geometry(14f), motion.shown, 0f)
        assertFalse(motion.animating(1120L))
    }
}
