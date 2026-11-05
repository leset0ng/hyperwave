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
}
