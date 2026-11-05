package dev.hyperwave.ui;

import android.graphics.Path;
import android.graphics.RectF;
import java.lang.reflect.Field;

/** Original per-vertex interpolation, with the measured atlas shared by both versions. */
final class ReferenceCorners {
    private static final int STEPS = 48;
    private final float[] aspects;
    private final float[] radii;
    private final float[][][] edges;
    private final Path path = new Path();

    ReferenceCorners() throws Exception {
        aspects = (float[]) field("ASPECTS");
        radii = (float[]) field("RADII");
        edges = (float[][][]) field("EDGES");
    }

    Path build(RectF bounds, float radius) {
        path.rewind();
        float width = bounds.width(), height = bounds.height();
        if (!(width > 0) || !(height > 0) || !Float.isFinite(width) || !Float.isFinite(height)) return path;
        if (!(radius > 0) || !Float.isFinite(radius)) {
            path.addRect(bounds, Path.Direction.CW);
            return path;
        }
        float aspect = height / width, relativeRadius = radius / width;
        int ai = lower(aspects, aspect), ri = lower(radii, relativeRadius);
        float at = fraction(aspects[ai], aspects[ai + 1], aspect);
        float rt = fraction(radii[ri], radii[ri + 1], relativeRadius);
        float cap = Math.min(1, aspect / 2);
        float left = bounds.left, right = bounds.right, top = bounds.top, bottom = bounds.bottom;
        path.moveTo(left + width / 2, top);
        for (int i = 1; i <= STEPS; i++) {
            float y = cap * i / STEPS;
            path.lineTo(left + width * edge(ai, ri, at, rt, y), top + width * y);
        }
        path.lineTo(left + width * edge(ai, ri, at, rt, cap), bottom - width * cap);
        for (int i = STEPS - 1; i >= 0; i--) {
            float y = cap * i / STEPS;
            path.lineTo(left + width * edge(ai, ri, at, rt, y), bottom - width * y);
        }
        for (int i = 1; i <= STEPS; i++) {
            float y = cap * i / STEPS;
            path.lineTo(right - width * edge(ai, ri, at, rt, y), bottom - width * y);
        }
        path.lineTo(right - width * edge(ai, ri, at, rt, cap), top + width * cap);
        for (int i = STEPS - 1; i >= 0; i--) {
            float y = cap * i / STEPS;
            path.lineTo(right - width * edge(ai, ri, at, rt, y), top + width * y);
        }
        path.close();
        return path;
    }

    private float edge(int ai, int ri, float at, float rt, float y) {
        float position = Math.max(0, Math.min(STEPS, y * STEPS));
        int index = Math.min(STEPS - 1, (int) position);
        float yt = position - index;
        float a = sample(edges[ai][ri], index, yt), b = sample(edges[ai][ri + 1], index, yt);
        float c = sample(edges[ai + 1][ri], index, yt), d = sample(edges[ai + 1][ri + 1], index, yt);
        return (a + (b - a) * rt) * (1 - at) + (c + (d - c) * rt) * at;
    }

    private static float sample(float[] values, int index, float fraction) {
        return values[index] + (values[index + 1] - values[index]) * fraction;
    }

    private static int lower(float[] grid, float value) {
        int index = 0;
        while (index < grid.length - 2 && value > grid[index + 1]) index++;
        return index;
    }

    private static float fraction(float low, float high, float value) {
        return Math.max(0, Math.min(1, (value - low) / (high - low)));
    }

    private static Object field(String name) throws Exception {
        Field field = ContinuousCorners.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
