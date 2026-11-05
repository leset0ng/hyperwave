package dev.hyperwave.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HyperOsFallbackTest {
    @Test public void startsAtNativeHeightsAndIndependentGradientEndpoints() {
        float[] levels = new float[6];
        float[] start = new float[5];
        float[] end = new float[5];
        HyperOsFallback.sample(0, levels, start, end);
        float[] geometry = new float[24];
        WaveformGeometry.calculate(false, levels, 66, true, true, geometry);
        float[] expected = {10.962f, 28.938f, 44.718f, 19.986f, 28.938f};
        float[] halfGradients = {5.313f, 11.784f, 19.659f, 7.302f, 11.784f};
        for (int i = 0; i < 5; i++) {
            assertEquals(expected[i], geometry[i * 4], .0001);
            assertEquals(-halfGradients[i], start[i], .0001);
            assertEquals(halfGradients[i], end[i], .0001);
        }
        assertEquals(0, levels[5], 0);
    }

    @Test public void nativeTracksDifferFromIosDurationAndPermutation() {
        float[] levels = new float[6];
        float[] start = new float[5];
        float[] end = new float[5];
        HyperOsFallback.sample(417, levels, start, end);
        assertEquals(1, levels[0], .0001);
        assertEquals(0, levels[2], .0001);
        assertEquals(levels[1], levels[4], 0);
        assertEquals(-start[0], end[0], .000001);
    }
}
