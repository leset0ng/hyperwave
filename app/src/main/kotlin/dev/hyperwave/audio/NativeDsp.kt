package dev.hyperwave.audio

import android.media.AudioRecord
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

    interface CaptureCallback {
        fun isStopped(): Boolean
        fun onBlock(sixBars: FloatArray, peak: Float, block: Long)
    }

    /** Blocks on the calling audio thread; PCM and the analyzer remain in native memory. */
    @JvmStatic
    external fun capture(record: AudioRecord, callback: CaptureCallback)

    @JvmStatic
    external fun analyze(mono4096: FloatArray, sixBars: FloatArray): Boolean

    /** Returns the running HSP sum, or -1 if the pixel tile is invalid. */
    @JvmStatic
    external fun sumArtworkBrightness(argb: IntArray, count: Int, initial: Double): Double

    /** Blurs the first 96 * 96 straight-alpha ARGB pixels in place. */
    @JvmStatic
    external fun blurArtwork(argb: IntArray, radius: Int): Boolean
}
