package ng.lst.hyperwave.audio

import android.content.Context
import android.media.AudioRecord
import java.io.File

object NativeDsp {
    @Volatile
    private var loaded = false
    private var reloadCopy: File? = null

    @Synchronized
    fun load(nativeLibraryDir: String) {
        if (loaded) return
        System.load(File(nativeLibraryDir, "libhyperwave_dsp.so").absolutePath)
        loaded = true
    }

    /** ART associates a library path with one ClassLoader; every generation needs a real copy. */
    @Synchronized
    fun loadForReload(context: Context, nativeLibraryDir: String) {
        if (loaded) return
        val directory = File(context.createDeviceProtectedStorageContext().codeCacheDir, "hyperwave-native")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create native reload directory" }
        val copy = File.createTempFile("libhyperwave_dsp-", ".so", directory)
        try {
            File(nativeLibraryDir, "libhyperwave_dsp.so").inputStream().use { input ->
                copy.outputStream().use { output -> input.copyTo(output) }
            }
            check(copy.setReadOnly()) { "Cannot protect native reload copy" }
            System.load(copy.absolutePath)
            reloadCopy = copy
            loaded = true
        } catch (error: Throwable) {
            copy.delete()
            throw error
        }
    }

    /** Called only after all capture and artwork JNI calls have returned. ART owns unloading. */
    @Synchronized
    fun retireReloadCopy() {
        reloadCopy?.delete()
        reloadCopy = null
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
