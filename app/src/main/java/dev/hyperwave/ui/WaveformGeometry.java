package dev.hyperwave.ui;

/** Target geometry in physical pixels: height, width, radius, horizontal center. */
final class WaveformGeometry {
    private WaveformGeometry() {}

    static void calculate(boolean ios, float[] levels, float size, boolean fallback,
                          boolean playing, float[] output) {
        float base = size * 5f / 48f;
        float slot = base * 1.12f;
        float pitch = slot + (size - 6 * slot) / 5;
        for (int i = 0; i < 6; i++) {
            int offset = i * 4;
            if (ios) {
                float value = Math.max(0, Math.min(1, levels[i]));
                boolean canned = fallback && i < 5;
                float width = (canned ? size / 8 : base) * (1 + .12f * value);
                output[offset] = Math.max(base, value * size);
                output[offset + 1] = width;
                output[offset + 2] = canned ? width / 2 : Math.round(width / 2);
                output[offset + 3] = i * pitch + slot / 2;
            } else {
                // Resample all six DSP bands onto the five native visual columns.
                float position = i * 5f / 4;
                int left = Math.min(5, (int) position);
                int right = Math.min(5, left + 1);
                float level = fallback ? levels[Math.min(i, 4)]
                        : levels[left] + (levels[right] - levels[left]) * (position - left);
                float value = Math.max(0, Math.min(1, level));
                output[offset] = i == 5 ? 0 : size / 66 * (playing ? 10.962f + 33.756f * value : 17.4f);
                output[offset + 1] = i == 5 ? 0 : size * 5.4f / 66;
                output[offset + 2] = output[offset + 1] / 2;
                output[offset + 3] = size * (9 + 12 * Math.min(i, 4)) / 66;
            }
        }
    }
}
