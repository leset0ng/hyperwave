package io.github.leset0ng.hyperwave.audio

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException

/** Exercises distinct ART library owners while preceding generations remain reachable. */
internal object NativeReloadCheck {
    fun verify(context: Context) {
        val application = context.applicationContext as Application
        NativeDsp.load(application.applicationInfo.nativeLibraryDir)
        val directory = File(application.createDeviceProtectedStorageContext().codeCacheDir, "hyperwave-native")
        val before = directory.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val generations = ArrayList<Pair<Class<*>, Any>>()
        try {
            repeat(3) {
                val loader = PathClassLoader(context.applicationInfo.sourceDir,
                        NativeDsp::class.java.classLoader!!.parent)
                val type = loader.loadClass("io.github.leset0ng.hyperwave.audio.NativeDsp")
                check(type !== NativeDsp::class.java) { "Native test delegated to the original module" }
                val instance = checkNotNull(type.getField("INSTANCE").get(null))
                generations.add(type to instance)
                val load = type.getMethod("loadForReload", Application::class.java, String::class.java)
                val foreignApplication = object : Application() {
                    override fun getPackageName(): String = "foreign.plugin"
                    override fun getApplicationInfo(): ApplicationInfo =
                            ApplicationInfo(application.applicationInfo).apply { uid = Process.myUid() + 1 }
                    override fun createDeviceProtectedStorageContext(): Context =
                            error("Foreign storage must not be accessed")
                }
                try {
                    load.invoke(instance, foreignApplication, application.applicationInfo.nativeLibraryDir)
                    error("Native reload accepted a foreign application UID")
                } catch (expected: InvocationTargetException) {
                    check(expected.cause is IllegalStateException &&
                            expected.cause?.message?.contains("requires the host application") == true)
                }
                load.invoke(instance, application, application.applicationInfo.nativeLibraryDir)
                load.invoke(instance, application, application.applicationInfo.nativeLibraryDir)
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
