package dev.hyperwave.ui;

import java.lang.reflect.Field;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ContinuousCornersTest {
    private static final int STEPS = 48;

    @Test public void samplesPreserveOriginalInterpolationAcrossAtlasBoundaries() throws Exception {
        float[] aspects = {0.25f, 1, 1.125f, 1.25f, 1.375f, 1.5f, 1.625f, 1.75f, 2, 4, 10, 20};
        float[] radii = {0.1f, .4f, .45f, .5f, .55f, .6f, 1};
        float[] widths = {1, 3.75f, 5.4f, 9.9f};
        float[] samples = new float[STEPS + 2];
        for (float width : widths) {
            for (float aspect : aspects) {
                for (float radius : radii) verify(width, width * aspect, width * radius, samples);
            }
        }
    }

    @Test public void reusedSamplesPreserveOriginalCoordinatesForAnimatedGeometry() throws Exception {
        Random random = new Random(139);
        float[] samples = new float[STEPS + 2];
        for (int i = 0; i < 1000; i++) {
            float width = 1 + random.nextFloat() * 20;
            float height = .1f + random.nextFloat() * 200;
            float radius = .01f + random.nextFloat() * width;
            verify(width, height, radius, samples);
        }
    }

    @Test public void directCapIsPreservedWhenSampleStepRoundsDifferently() throws Exception {
        float width = 1;
        float height = .8000001f;
        float cap = Math.min(1, height / width / 2);
        // Search adjacent representable heights to exercise the separate join sample.
        while (cap * STEPS / STEPS == cap) {
            height = Math.nextUp(height);
            cap = Math.min(1, height / width / 2);
        }
        assertNotEquals(Float.floatToIntBits(cap), Float.floatToIntBits(cap * STEPS / STEPS));
        verify(width, height, .5f, new float[STEPS + 2]);
    }

    private static void verify(float width, float height, float radius, float[] samples) throws Exception {
        float cap = ContinuousCorners.sampleEdges(width, height, radius, samples);
        assertBits(Math.min(1, height / width / 2), cap);
        for (int i = 0; i <= STEPS; i++) {
            float y = cap * i / STEPS;
            float original = originalEdge(width, height, radius, y);
            assertBits(original, samples[i]);
            // Check both mirrored x coordinates at a nonzero origin as used by the path builder.
            assertBits(7.25f + width * original, 7.25f + width * samples[i]);
            assertBits(7.25f + width - width * original, 7.25f + width - width * samples[i]);
        }
        assertBits(originalEdge(width, height, radius, cap), samples[STEPS + 1]);
    }

    // Independent copy of the original per-vertex interpolation, using the unchanged measured atlas.
    private static float originalEdge(float width, float height, float radius, float y) throws Exception {
        float[] aspects = (float[]) field("ASPECTS");
        float[] radii = (float[]) field("RADII");
        float[][][] edges = (float[][][]) field("EDGES");
        float aspect = height / width;
        float relativeRadius = radius / width;
        int ai = 0;
        while (ai < aspects.length - 2 && aspect > aspects[ai + 1]) ai++;
        int ri = 0;
        while (ri < radii.length - 2 && relativeRadius > radii[ri + 1]) ri++;
        float at = Math.max(0, Math.min(1, (aspect - aspects[ai]) / (aspects[ai + 1] - aspects[ai])));
        float rt = Math.max(0, Math.min(1, (relativeRadius - radii[ri]) / (radii[ri + 1] - radii[ri])));
        float position = Math.max(0, Math.min(STEPS, y * STEPS));
        int index = Math.min(STEPS - 1, (int) position);
        float yt = position - index;
        float a = interpolate(edges[ai][ri], index, yt);
        float b = interpolate(edges[ai][ri + 1], index, yt);
        float c = interpolate(edges[ai + 1][ri], index, yt);
        float d = interpolate(edges[ai + 1][ri + 1], index, yt);
        return (a + (b - a) * rt) * (1 - at) + (c + (d - c) * rt) * at;
    }

    private static Object field(String name) throws Exception {
        Field field = ContinuousCorners.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static float interpolate(float[] values, int index, float fraction) {
        return values[index] + (values[index + 1] - values[index]) * fraction;
    }

    private static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual));
    }
}
