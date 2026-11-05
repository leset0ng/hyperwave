package ng.lst.hyperwave.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Looper
import android.os.Process
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A UID-filtered playback copy. This never redirects the original output. */
class PlaybackCapture(
        private val context: Context,
        private val uid: Int,
        private val listener: Listener,
) : Runnable {
    interface Listener {
        fun onBars(bars: FloatArray)
        fun onUnavailable(reason: String)
    }

    private val lifecycle = CaptureLifecycle<AudioRecord>(
            "HyperWave-stop-$uid",
            stopRecord = { current ->
                try {
                    current.stop()
                } catch (ignored: IllegalStateException) {
                }
            },
            releaseRecord = { it.release() },
    )
    private val stopped: Boolean get() = lifecycle.isStopped
    private val thread = Thread(this, "HyperWave-capture-$uid")

    fun start() {
        active.add(this)
        try {
            thread.start()
        } catch (error: Throwable) {
            active.remove(this)
            throw error
        }
    }

    fun stop() {
        lifecycle.stop()
    }

    override fun run() {
        try {
            runSession()
        } finally {
            try {
                lifecycle.awaitStopped()?.let { error ->
                    cleanupFailed = true
                    Log.e(TAG, "Cannot stop audio record", error)
                }
            } finally {
                active.remove(this)
            }
        }
    }

    private fun runSession() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val manager = context.getSystemService(AudioManager::class.java)
        var captureStarted = false
        var attemptCleanedUp = false
        try {
            runCaptureWithFallback(
                    isStopped = { stopped },
                    canRetry = { !captureStarted && attemptCleanedUp },
                    capture = { privileged ->
                        attemptCleanedUp = false
                        captureAttempt(manager, privileged,
                                onStarted = { captureStarted = true },
                                onReleased = { attemptCleanedUp = true })
                    },
                    onFallback = { error ->
                        val cause = captureCause(error)
                        Log.w(TAG, "privileged capture rejected uid=$uid; retrying legacy 48000Hz mono float32", cause)
                    },
            )
        } catch (error: Throwable) {
            val cause = captureCause(error)
            if (!stopped) {
                Log.e(TAG, "capture unavailable uid=$uid", cause)
                listener.onUnavailable(cause.javaClass.simpleName + ": " + cause.message)
            }
        }
    }

    private fun captureAttempt(
            manager: AudioManager,
            privileged: Boolean,
            onStarted: () -> Unit,
            onReleased: () -> Unit,
    ) {
        if (stopped) return
        var policy: Any? = null
        lateinit var policyClass: Class<*>
        var registered = false
        try {
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            ruleBuilderClass.getMethod("setTargetMixRole", Int::class.java).invoke(ruleBuilder, 0)
            ruleBuilderClass.getMethod("addMixRule", Int::class.java, Any::class.java).invoke(ruleBuilder, 4, uid)
            val attributesBuilder = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            if (privileged) {
                AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
                        .invoke(attributesBuilder, RULE_TAG)
            }
            val attributes = attributesBuilder.build()
            ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.java)
                    .invoke(ruleBuilder, attributes, 1)
            // Try the marked system path first, then the original unprivileged rules.
            // Both attempts retain the same 48kHz Float32 recording format.
            ruleBuilderClass.getMethod("allowPrivilegedPlaybackCapture", Boolean::class.java)
                    .invoke(ruleBuilder, privileged)
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
            val format = AudioFormat.Builder().setSampleRate(48000)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.java).invoke(mixBuilder, 3)
            val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

            policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
            policyBuilderClass.getMethod("setLooper", Looper::class.java).invoke(policyBuilder, Looper.getMainLooper())
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
            policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)
            if (stopped) return
            val result = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass)
                    .invoke(manager, policy) as Int
            if (result != 0) throw IllegalStateException("registerAudioPolicy=$result")
            registered = true
            val current = policyClass.getMethod("createAudioRecordSink", mixClass)
                    .invoke(policy, mix) as AudioRecord?
            if (current == null) throw IllegalStateException("Playback AudioRecord not initialized")
            if (!lifecycle.start(current) {
                if (it.state != AudioRecord.STATE_INITIALIZED)
                    throw IllegalStateException("Playback AudioRecord not initialized")
                if (it.sampleRate != 48000 || it.channelCount != 1
                        || it.audioFormat != AudioFormat.ENCODING_PCM_FLOAT)
                    throw IllegalStateException("Unexpected playback capture format")
                it.startRecording()
                if (it.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                    throw IllegalStateException("Playback AudioRecord did not start")
                onStarted()
            }) return
            Log.i(TAG, "capture path uid=$uid mode=${if (privileged) "privileged" else "legacy"}")
            Log.i(TAG, "capture started uid=$uid 48000Hz mono float32 reader=native")
            NativeDsp.capture(current, object : NativeDsp.CaptureCallback {
                override fun isStopped(): Boolean = stopped

                override fun onBlock(sixBars: FloatArray, peak: Float, block: Long) {
                    if (stopped) return
                    if (block == 1L || block % 24 == 0L) {
                        Log.i(TAG, "PCM block=$block uid=$uid peak=$peak bars=${sixBars.contentToString()}")
                    }
                    listener.onBars(sixBars)
                }
            })
        } finally {
            var released = true
            try {
                lifecycle.release()
            } catch (error: RuntimeException) {
                released = false
                Log.e(TAG, "Cannot stop or release audio record", error)
            }
            if (registered) {
                try {
                    AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass).invoke(manager, policy)
                } catch (error: ReflectiveOperationException) {
                    released = false
                    Log.e(TAG, "Cannot unregister audio policy", error)
                }
            }
            if (released) onReleased() else cleanupFailed = true
            Log.i(TAG, "capture stopped uid=$uid")
        }
    }

    private fun captureCause(error: Throwable): Throwable =
            if (error is InvocationTargetException && error.cause != null) error.cause!! else error

    companion object {
        private val active = ConcurrentHashMap.newKeySet<PlaybackCapture>()
        @Volatile private var cleanupFailed = false

        /** Also waits for capture and stop workers already retiring after a hide/source change. */
        internal fun stopAllAndAwait(timeoutMillis: Long): Boolean {
            val sessions = active.toList()
            sessions.forEach { it.stop() }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            for (session in sessions) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                TimeUnit.NANOSECONDS.timedJoin(session.thread, remaining)
                if (session.thread.isAlive) return false
            }
            return active.isEmpty() && !cleanupFailed
        }

        internal const val RULE_TAG = "ng.lst.hyperwave.capture.48k.v1"
        private const val TAG = "HyperWave"
    }
}

/** Each attempt owns its cleanup; fallback starts only after the first attempt unwinds. */
internal fun runCaptureWithFallback(
        isStopped: () -> Boolean,
        canRetry: () -> Boolean,
        capture: (privileged: Boolean) -> Unit,
        onFallback: (Exception) -> Unit,
) {
    if (isStopped()) return
    try {
        capture(true)
    } catch (first: Exception) {
        if (isStopped()) return
        if (!canRetry()) throw first
        onFallback(first)
        if (isStopped()) return
        try {
            capture(false)
        } catch (second: Exception) {
            if (second !== first) second.addSuppressed(first)
            throw second
        }
    }
}
