package io.github.leset0ng.hyperwave.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.DoubleUnaryOperator

class GeometryTransitionTest {
    private val motion = GeometryTransition(DoubleUnaryOperator { it }, DoubleUnaryOperator { it })

    private fun geometry(value: Float): FloatArray = FloatArray(GeometryTransition.COMPONENTS) { value }

    @Test
    fun settledIdenticalGeometryDoesNotRestartFrames() {
        motion.reset(geometry(2f))
        assertFalse(motion.transition(geometry(2f), 1000L, 80L))
        assertFalse(motion.animating(1000L))
        assertTrue(motion.transition(geometry(10f), 1000L, 80L))
        motion.evaluate(1080L)
        repeat(60) {
            val now = 1085L + it * 85L
            assertFalse(motion.transition(geometry(10f), now, 80L))
            assertFalse(motion.animating(now))
            assertArrayEquals(geometry(10f), motion.shown, 0f)
        }
    }

    @Test
    fun repeatedTargetDuringTransitionRetainsRestartTiming() {
        motion.reset(geometry(0f))
        motion.transition(geometry(8f), 1000L, 80L)
        assertTrue(motion.transition(geometry(8f), 1040L, 80L))
        assertArrayEquals(geometry(4f), motion.shown, 0f)
        motion.evaluate(1080L)
        assertArrayEquals(geometry(6f), motion.shown, 0f)
        assertTrue(motion.animating(1080L))
        motion.evaluate(1120L)
        assertArrayEquals(geometry(8f), motion.shown, 0f)
        assertFalse(motion.animating(1120L))
    }

    @Test
    fun changingTargetToCurrentPresentationInterruptsTheOldTransition() {
        motion.reset(geometry(0f))
        motion.transition(geometry(8f), 1000L, 80L)
        assertTrue(motion.transition(geometry(4f), 1040L, 80L))
        motion.evaluate(1080L)
        assertArrayEquals(geometry(4f), motion.shown, 0f)
    }

    @Test
    fun fallbackReturningToThePreviousTargetStillTransitions() {
        motion.reset(geometry(2f))
        motion.display(geometry(15f))
        assertTrue(motion.transition(geometry(2f), 1000L, 80L))
        assertArrayEquals(geometry(15f), motion.shown, 0f)
        motion.evaluate(1080L)
        assertArrayEquals(geometry(2f), motion.shown, 0f)
        assertFalse(motion.animating(1080L))
    }

    @Test
    fun evenOneUlpOfGeometryChangeIsPreserved() {
        motion.reset(geometry(2f))
        val next = geometry(2f)
        next[0] = Math.nextUp(2f)
        assertTrue(motion.transition(next, 1000L, 80L))
        motion.evaluate(1080L)
        assertArrayEquals(next, motion.shown, 0f)
    }

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
