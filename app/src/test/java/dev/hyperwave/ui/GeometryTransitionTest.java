package dev.hyperwave.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GeometryTransitionTest {
    private final GeometryTransition motion = new GeometryTransition(value -> value, value -> value);

    private static float[] geometry(float value) {
        float[] result = new float[GeometryTransition.COMPONENTS];
        java.util.Arrays.fill(result, value);
        return result;
    }

    @Test public void fallbackToPauseStartsAtTheDisplayedFrame() {
        motion.reset(geometry(2));
        motion.display(geometry(15));
        motion.transition(geometry(3), 5000, 900);
        motion.evaluate(5000);
        assertArrayEquals(geometry(15), motion.shown, 0);
        motion.evaluate(5450);
        assertArrayEquals(geometry(9), motion.shown, .000001f);
        motion.evaluate(5900);
        assertArrayEquals(geometry(3), motion.shown, 0);
    }

    @Test public void fallbackToLiveDoesNotReuseAnOldAnimationTarget() {
        motion.reset(geometry(2));
        motion.transition(geometry(8), 1000, 80);
        motion.display(geometry(17));
        motion.transition(geometry(5), 2000, 80);
        motion.evaluate(2000);
        assertArrayEquals(geometry(17), motion.shown, 0);
        motion.evaluate(2080);
        assertArrayEquals(geometry(5), motion.shown, 0);
    }

    @Test public void liveUpdatesResumeTheCurrentPresentation() {
        motion.reset(geometry(2));
        motion.transition(geometry(10), 1000, 80);
        motion.transition(geometry(14), 1040, 80);
        motion.evaluate(1040);
        assertArrayEquals(geometry(6), motion.shown, 0);
        assertTrue(motion.animating(1040));
        motion.evaluate(1120);
        assertArrayEquals(geometry(14), motion.shown, 0);
        assertFalse(motion.animating(1120));
    }
}
