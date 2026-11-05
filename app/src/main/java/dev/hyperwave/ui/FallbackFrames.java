package dev.hyperwave.ui;

/** Recovered VariantA heights; linear keyframe interpolation is an MVP approximation.
 * The sixth bar stays at its minimum because its reference fallback behavior is unresolved.
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
    static float[] sample(float cycles) {
        float phase = cycles - (float) Math.floor(cycles);
        float[] output = new float[6];
        for (int band = 0; band < 5; band++) {
            float[] times = TIMES[band == 0 ? 0 : 1];
            int i = 0;
            while (i < times.length - 2 && phase > times[i + 1]) i++;
            float fraction = (phase - times[i]) / (times[i + 1] - times[i]);
            output[band] = HEIGHTS[band][i] + (HEIGHTS[band][i + 1] - HEIGHTS[band][i]) * fraction;
        }
        return output;
    }
}
