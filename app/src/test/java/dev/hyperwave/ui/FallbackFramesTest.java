package dev.hyperwave.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FallbackFramesTest {
    @Test public void matchesQuartzCorePresentationSamples() {
        float[] phases = {0, .0455f, .091f, .1364f, .1818f, .2274f, .273f, .9545f, 1};
        double[] reference = {49, 50.6316694169417, 51, 44.829251834524115, 42.9,
                61.2304207014108, 78.9, 61.44931968114346, 49};
        float[] output = new float[6];
        for (int i = 0; i < phases.length; i++) {
            FallbackFrames.sample(phases[i], 0, output);
            assertEquals("phase=" + phases[i], reference[i] / 100, output[0], .000002);
            assertEquals(0, output[5], 0);
        }
    }

    @Test public void variantsPermuteTheRecoveredTracks() {
        float[][] expected = {{.49f, .77f, .6f, .33f, .4f, 0},
                {.4f, .6f, .49f, .77f, .33f, 0}, {.33f, .49f, .77f, .6f, .4f, 0}};
        float[] output = new float[6];
        for (int variant = 0; variant < 3; variant++) {
            FallbackFrames.sample(0, variant, output);
            assertArrayEquals(expected[variant], output, .000001f);
        }
    }

    @Test public void loopsWithoutChangingEndpointTangents() {
        float[] before = new float[6];
        float[] after = new float[6];
        FallbackFrames.sample(.001f, 0, before);
        FallbackFrames.sample(1.001f, 0, after);
        assertArrayEquals(before, after, .000001f);
        assertEquals(.4902258155101536, before[0], .000001);
        FallbackFrames.sample(.999f, 0, before);
        assertEquals(.4924209186681869, before[0], .000001);
    }
}
