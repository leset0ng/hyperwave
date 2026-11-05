package ng.lst.hyperwave.module

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Binder
import android.os.Process
import android.util.Log
import ng.lst.hyperwave.audio.PlaybackCapture
import io.github.libxposed.api.XposedInterface

/** Keeps the format exception local to a marked, UID-filtered music loopback. */
internal class FullRateCaptureHooks(
        private val module: XposedInterface,
        private val systemServer: Boolean,
) {
    private val scope = CaptureFormatScope()
    private var reportedInspectionFailure = false
    @Volatile private var systemUiUid = -1

    // These hooks run under LSPosed in framework processes with hidden-API access.
    @SuppressLint("BlockedPrivateApi")
    fun install(loader: ClassLoader) {
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix", false, loader)
        val builderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder", false, loader)
        val validate = mixClass.getDeclaredMethod("canBeUsedForPrivilegedMediaCapture", AudioFormat::class.java)
        module.hook(validate).intercept { chain ->
            if (scope.allows(chain.getArg(0))) null else chain.proceed()
        }
        val build = builderClass.getDeclaredMethod("build")
        module.hook(build).intercept { chain ->
            val format = inspect {
                if (!trustedCaller()) return@inspect null
                val builder = chain.thisObject ?: return@inspect null
                eligibleFormat(Reflect.field(builder, "mRule"),
                        Reflect.field(builder, "mRouteFlags") as? Int,
                        Reflect.field(builder, "mFormat") as? AudioFormat)
            }
            if (format == null) chain.proceed() else scope.withFormat(format) { chain.proceed() }
        }
        module.deoptimize(build)

        if (systemServer) {
            val service = Class.forName("com.android.server.audio.AudioService", false, loader)
            val allowed = service.declaredMethods.single { it.name == "isPolicyRegisterAllowed" }
            module.hook(allowed).intercept { chain ->
                val format = inspect {
                    if (!trustedCaller()) return@inspect null
                    val config = chain.getArg(0) ?: return@inspect null
                    val mixes = Reflect.call(config, "getMixes") as? List<*> ?: return@inspect null
                    if (mixes.size != 1) return@inspect null
                    val mix = mixes.single() ?: return@inspect null
                    eligibleFormat(Reflect.call(mix, "getRule"),
                            Reflect.call(mix, "getRouteFlags") as? Int,
                            Reflect.call(mix, "getFormat") as? AudioFormat)
                }
                // Run AudioService's original permission and projection checks unchanged.
                if (format == null) chain.proceed() else scope.withFormat(format) { chain.proceed() }
            }
            module.deoptimize(allowed)
        }
        module.log(Log.INFO, TAG, "48kHz privileged capture hooks installed systemServer=$systemServer")
    }

    private fun trustedCaller(): Boolean {
        if (!systemServer) return Binder.getCallingUid() == Process.myUid()
        val caller = Binder.getCallingUid()
        if (caller == Process.myUid()) return false
        if (systemUiUid >= 0) return caller == systemUiUid
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null) ?: return false
        val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val uid = context.packageManager.getApplicationInfo(SYSTEM_UI, 0).uid
        systemUiUid = uid
        return caller == uid
    }

    private fun eligibleFormat(rule: Any?, routes: Int?, format: AudioFormat?): AudioFormat? {
        if (rule == null || routes != 3 || format == null) return null
        if (format.sampleRate != 48000 || format.encoding != AudioFormat.ENCODING_PCM_FLOAT
                || format.channelMask != AudioFormat.CHANNEL_OUT_MONO) return null
        if (Reflect.call(rule, "getTargetMixRole") != 0
                || Reflect.call(rule, "allowPrivilegedMediaPlaybackCapture") != true
                || Reflect.call(rule, "voiceCommunicationCaptureAllowed") != false) return null
        val criteria = Reflect.call(rule, "getCriteria") as? List<*> ?: return null
        if (criteria.size != 2) return null
        var uid = -1
        var markedMedia = false
        for (criterion in criteria) {
            if (criterion == null) return null
            when (Reflect.call(criterion, "getRule")) {
                4 -> uid = Reflect.call(criterion, "getIntProp") as Int
                1 -> {
                    val attributes = Reflect.call(criterion, "getAudioAttributes") as? AudioAttributes
                            ?: return null
                    val tags = Reflect.call(attributes, "getTags") as? Set<*> ?: return null
                    markedMedia = attributes.usage == AudioAttributes.USAGE_MEDIA
                            && tags == setOf(PlaybackCapture.RULE_TAG)
                }
                else -> return null
            }
        }
        return if (uid >= 10000 && markedMedia) format else null
    }

    private fun inspect(action: () -> AudioFormat?): AudioFormat? = try {
        action()
    } catch (error: ReflectiveOperationException) {
        reportInspectionFailure(error)
        null
    } catch (error: RuntimeException) {
        reportInspectionFailure(error)
        null
    }

    private fun reportInspectionFailure(error: Exception) {
        if (reportedInspectionFailure) return
        reportedInspectionFailure = true
        module.log(Log.ERROR, TAG, "Cannot verify marked capture; retaining format restrictions", error)
    }

    private companion object {
        const val TAG = "HyperWave"
        const val SYSTEM_UI = "com.android.systemui"
    }
}

/** Thread-local identity matching prevents a format exemption from escaping its operation. */
internal class CaptureFormatScope {
    private val current = ThreadLocal<Any>()

    fun allows(format: Any?): Boolean = format != null && current.get() === format

    fun <T> withFormat(format: Any, action: () -> T): T {
        val previous = current.get()
        current.set(format)
        try {
            return action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
