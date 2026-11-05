package dev.hyperwave.ui;

/** Recovered BouncyBars trajectories and default Core Animation scalar cubic.
 * Hermite tangents were checked against public macOS QuartzCore presentation values.
 */
final class FallbackFrames {
    private static final float[][] TIMES = {
        {0, .091f, .1818f, .273f, .3636f, .4545f, .5454f, .6363f, .7272f, .8181f, .909f, 1},
        {0, .091f, .1818f, .2727f, .3636f, .4545f, .5454f, .6363f, .7272f, .8181f, .909f, 1}
    };
    private static final float[][] HEIGHTS = {
        {.49f, .51f, .429f, .789f, .655f, .31f, .473f, .51f, .532f, .688f, .709f, .49f},
        {.77f, .48f, .77f, .552f, .451f, .461f, .881f, .7f, .907f, .534f, .519f, .77f},
        {.6f, .68f, .58f, .829f, .68f, .79f, .614f, .88f, .571f, .829f, .62f, .6f},
        {.33f, .589f, .68f, .469f, .937f, .599f, .717f, .52f, .688f, .44f, .73f, .33f},
        {.4f, .24f, .489f, .57f, .421f, .339f, .25f, .72f, .29f, .588f, .501f, .4f}
    };
    private static final int[][] VARIANTS = {
        {0, 1, 2, 3, 4}, {4, 2, 0, 1, 3}, {3, 0, 1, 2, 4}
    };

    static void sample(double cycles, int variant, float[] output) {
        float phase = (float) (cycles - Math.floor(cycles));
        for (int band = 0; band < 5; band++) {
            int track = VARIANTS[variant][band];
            float[] times = TIMES[track == 0 ? 0 : 1];
            float[] values = HEIGHTS[track];
            int i = 0;
            while (i < times.length - 2 && phase > times[i + 1]) i++;
            float dt = times[i + 1] - times[i];
            float t = (phase - times[i]) / dt;
            float t2 = t * t;
            float t3 = t2 * t;
            output[band] = (2 * t3 - 3 * t2 + 1) * values[i]
                    + (t3 - 2 * t2 + t) * dt * tangent(times, values, i)
                    + (-2 * t3 + 3 * t2) * values[i + 1]
                    + (t3 - t2) * dt * tangent(times, values, i + 1);
        }
        output[5] = 0;
    }

    private static float tangent(float[] times, float[] values, int index) {
        int left = Math.max(0, index - 1);
        int right = Math.min(values.length - 1, index + 1);
        return (values[right] - values[left]) / (times[right] - times[left]);
    }
}
