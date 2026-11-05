package dev.hyperwave.audio

import java.io.File

object NativeDsp {
    @Volatile
    private var loaded = false

    @Synchronized
    fun load(nativeLibraryDir: String) {
        if (loaded) return
        System.load(File(nativeLibraryDir, "libhyperwave_dsp.so").absolutePath)
        loaded = true
    }

    @JvmStatic
    external fun analyze(mono4096: FloatArray, sixBars: FloatArray): Boolean

    /** Returns the running HSP sum, or -1 if the pixel tile is invalid. */
    @JvmStatic
    external fun sumArtworkBrightness(argb: IntArray, count: Int, initial: Double): Double

    /** Blurs the first 96 * 96 straight-alpha ARGB pixels in place. */
    @JvmStatic
    external fun blurArtwork(argb: IntArray, radius: Int): Boolean
}
