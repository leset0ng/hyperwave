package ng.lst.hyperwave.audio

import android.content.Context
import dalvik.system.PathClassLoader
import java.io.File

/** Exercises distinct ART library owners while preceding generations remain reachable. */
internal object NativeReloadCheck {
    fun verify(context: Context) {
        NativeDsp.load(context.applicationInfo.nativeLibraryDir)
        val directory = File(context.createDeviceProtectedStorageContext().codeCacheDir, "hyperwave-native")
        val before = directory.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val generations = ArrayList<Pair<Class<*>, Any>>()
        try {
            repeat(3) {
                val loader = PathClassLoader(context.applicationInfo.sourceDir,
                        NativeDsp::class.java.classLoader!!.parent)
                val type = loader.loadClass("ng.lst.hyperwave.audio.NativeDsp")
                check(type !== NativeDsp::class.java) { "Native test delegated to the original module" }
                val instance = type.getField("INSTANCE").get(null)
                generations.add(type to instance)
                val load = type.getMethod("loadForReload", Context::class.java, String::class.java)
                load.invoke(instance, context, context.applicationInfo.nativeLibraryDir)
                load.invoke(instance, context, context.applicationInfo.nativeLibraryDir)
                val created = directory.listFiles()!!.count { file -> file.name !in before }
                check(created == generations.size) { "Each generation must own exactly one native copy" }
                for ((generation, _) in generations) verifyCalls(generation)
                check(NativeDsp.analyze(FloatArray(4096), FloatArray(6)))
            }
        } finally {
            for ((type, instance) in generations) type.getMethod("retireReloadCopy").invoke(instance)
        }
        check(directory.listFiles()?.none { it.name !in before } != false) { "Native copies were not retired" }
    }

    private fun verifyCalls(type: Class<*>) {
        val bars = FloatArray(6)
        check(type.getMethod("analyze", FloatArray::class.java, FloatArray::class.java)
                .invoke(null, FloatArray(4096), bars) == true)
        check(bars.all { it.isFinite() })
        val brightness = type.getMethod("sumArtworkBrightness", IntArray::class.java,
                Int::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                .invoke(null, intArrayOf(-1), 1, 0.0) as Double
        check(brightness > 0 && brightness.isFinite())
        check(type.getMethod("blurArtwork", IntArray::class.java, Int::class.javaPrimitiveType)
                .invoke(null, IntArray(96 * 96), 26) == true)
    }
}
