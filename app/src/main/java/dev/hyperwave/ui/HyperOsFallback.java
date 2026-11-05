package dev.hyperwave.ui;

/** Scalar motion and gradient endpoints recovered from the native 66x66 music_wave. */
final class HyperOsFallback {
    private static final float[][] TIMES = {{0, 25, 49}, {0, 13, 36.543f, 49},
            {0, 25, 49}, {0, 6, 30.729f, 49}, {0, 13, 36.543f, 49}};
    private static final float[][] HEIGHTS = {{10.962f, 44.718f, 10.962f},
            {28.938f, 10.962f, 44.718f, 30.078f}, {44.718f, 10.962f, 44.628f},
            {19.986f, 10.962f, 44.718f, 21.192f}, {28.938f, 10.962f, 44.718f, 30.078f}};
    private static final float[][] GRADIENTS = {{1.771f, 6.553f, 1.786f},
            {3.928f, 1.771f, 6.553f, 4.172f}, {6.553f, 1.771f, 6.539f},
            {2.434f, 1.771f, 6.553f, 2.619f}, {3.928f, 1.771f, 6.553f, 4.172f}};
    private static final int[][] HEIGHT_CURVES = {{0, 0}, {1, 0, 2}, {0, 2}, {1, 0, 2}, {1, 0, 2}};
    private static final float[][] CURVES = {{.333f, 0, .667f, 1}, {.167f, .201f, .667f, 1},
            {.333f, 0, .833f, .799f}};

    private HyperOsFallback() {}

    static void sample(long elapsedMillis, float[] levels, float[] gradientStart, float[] gradientEnd) {
        float frame = (float) ((elapsedMillis * .06) % 49.99);
        for (int bar = 0; bar < 5; bar++) {
            float[] times = TIMES[bar];
            int i = 0;
            while (i < times.length - 2 && frame > times[i + 1]) i++;
            float fraction = Math.min(1, (frame - times[i]) / (times[i + 1] - times[i]));
            float[] curve = CURVES[HEIGHT_CURVES[bar][i]];
            float progress = ease(fraction, curve[0], curve[1], curve[2], curve[3]);
            float height = HEIGHTS[bar][i] + (HEIGHTS[bar][i + 1] - HEIGHTS[bar][i]) * progress;
            levels[bar] = (height - 10.962f) / 33.756f;
            float outY = i == 0 && (bar == 1 || bar == 4) ? .576f : i == 0 && bar == 3 ? .621f : 0;
            float inY = i == times.length - 2 ? (bar == 0 || bar == 2 ? .938f : bar == 3 ? .651f : .498f) : 1;
            float gradientProgress = ease(fraction, .333f, outY, .667f, inY);
            float half = GRADIENTS[bar][i] + (GRADIENTS[bar][i + 1] - GRADIENTS[bar][i]) * gradientProgress;
            gradientStart[bar] = -3 * half;
            float end = half;
            if ((bar == 1 || bar == 4) && i == times.length - 2) end -= .001f * gradientProgress;
            gradientEnd[bar] = 3 * end;
        }
        levels[5] = 0;
    }

    private static float ease(float fraction, float x1, float y1, float x2, float y2) {
        float low = 0, high = 1;
        for (int i = 0; i < 18; i++) {
            float t = (low + high) / 2;
            if (bezier(t, x1, x2) < fraction) low = t; else high = t;
        }
        return bezier((low + high) / 2, y1, y2);
    }

    private static float bezier(float t, float first, float second) {
        float inverse = 1 - t;
        return 3 * inverse * inverse * t * first + 3 * inverse * t * t * second + t * t * t;
    }
}
