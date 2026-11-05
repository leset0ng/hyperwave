package dev.hyperwave.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WaveformGeometryTest {
    @Test public void iosLiveAndFallbackUseTheirDistinctWidthsAndRadii() {
        float[] levels = {.49f, .77f, .6f, .33f, .4f, 0};
        float[] live = new float[24];
        float[] fallback = new float[24];
        WaveformGeometry.calculate(true, levels, 66, false, true, live);
        WaveformGeometry.calculate(true, levels, 66, true, true, fallback);
        for (int i = 0; i < 5; i++) {
            assertEquals(live[i * 4 + 1] * 1.2, fallback[i * 4 + 1], .000002);
            assertEquals(fallback[i * 4 + 1] / 2, fallback[i * 4 + 2], 0);
            assertEquals(live[i * 4 + 3], fallback[i * 4 + 3], 0);
        }
        assertEquals(live[20], fallback[20], 0);
        assertEquals(live[21], fallback[21], 0);
    }

    @Test public void hyperosRetainsNativeFiveBarGeometryAndPauseHeight() {
        float[] output = new float[24];
        WaveformGeometry.calculate(false, new float[6], 66, false, false, output);
        for (int i = 0; i < 5; i++) {
            assertEquals(17.4, output[i * 4], .000002);
            assertEquals(5.4, output[i * 4 + 1], .000001);
            assertEquals(2.7, output[i * 4 + 2], .000001);
            assertEquals(9 + 12 * i, output[i * 4 + 3], .000001);
        }
        assertEquals(0, output[20], 0);
        assertEquals(0, output[21], 0);
    }

    @Test public void hyperosWidthStaysFixedAndTheHighestBandIsNotDropped() {
        float[] levels = {0, 0, 0, 0, 0, 1};
        float[] output = new float[24];
        WaveformGeometry.calculate(false, levels, 66, false, true, output);
        assertEquals(10.962, output[0], .000002);
        assertEquals(44.718, output[16], .000004);
        assertEquals(output[1], output[17], 0);
    }

    @Test public void hyperosFallbackAnimatesAllFiveColumns() {
        float[] output = new float[24];
        WaveformGeometry.calculate(false, new float[]{1, 1, 1, 1, 1, 0}, 66, true, true, output);
        for (int i = 0; i < 5; i++) assertEquals(44.718, output[i * 4], .000004);
        assertEquals(0, output[20], 0);
    }
}
