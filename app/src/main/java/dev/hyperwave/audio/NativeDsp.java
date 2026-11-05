package dev.hyperwave.audio;

import java.io.File;

public final class NativeDsp {
    private static boolean loaded;
    private NativeDsp() {}

    public static synchronized void load(String nativeLibraryDir) {
        if (loaded) return;
        System.load(new File(nativeLibraryDir, "libhyperwave_dsp.so").getAbsolutePath());
        loaded = true;
    }

    public static native boolean analyze(float[] mono4096, float[] sixBars);

    /** Returns the running HSP sum, or -1 if the pixel tile is invalid. */
    public static native double sumArtworkBrightness(int[] argb, int count, double initial);

    /** Blurs the first 96 * 96 straight-alpha ARGB pixels in place. */
    public static native boolean blurArtwork(int[] argb, int radius);
}
