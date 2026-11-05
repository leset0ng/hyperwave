package dev.hyperwave.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Looper
import android.os.Process
import android.util.Log
import java.lang.reflect.InvocationTargetException

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

    @Volatile
    private var stopped = false

    @Volatile
    private var record: AudioRecord? = null
    private val thread = Thread(this, "Hyperwave-capture-$uid")

    fun start() {
        thread.start()
    }

    fun stop() {
        stopped = true
        val current = record
        if (current != null) {
            try {
                current.stop()
            } catch (ignored: IllegalStateException) {
            }
        }
        thread.interrupt()
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val manager = context.getSystemService(AudioManager::class.java)
        var policy: Any? = null
        lateinit var policyClass: Class<*>
        var registered = false
        try {
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            ruleBuilderClass.getMethod("setTargetMixRole", Int::class.java).invoke(ruleBuilder, 0)
            ruleBuilderClass.getMethod("addMixRule", Int::class.java, Any::class.java).invoke(ruleBuilder, 4, uid)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.java)
                    .invoke(ruleBuilder, attributes, 1)
            // Privileged=true restricts capture to <=16kHz PCM16. Keep the 48kHz
            // path explicit, with normal playback capture policy restrictions.
            ruleBuilderClass.getMethod("allowPrivilegedPlaybackCapture", Boolean::class.java)
                    .invoke(ruleBuilder, false)
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
            record = current
            if (current == null || current.state != AudioRecord.STATE_INITIALIZED)
                throw IllegalStateException("Playback AudioRecord not initialized")
            if (current.sampleRate != 48000 || current.channelCount != 1
                    || current.audioFormat != AudioFormat.ENCODING_PCM_FLOAT)
                throw IllegalStateException("Unexpected playback capture format")
            if (stopped) return
            current.startRecording()
            if (current.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                throw IllegalStateException("Playback AudioRecord did not start")
            Log.i(TAG, "capture started uid=$uid 48000Hz mono float32")
            val pcm = FloatArray(4096)
            var offset = 0
            var blocks = 0L
            while (!stopped) {
                val count = current.read(pcm, offset, pcm.size - offset, AudioRecord.READ_BLOCKING)
                if (stopped) break
                if (count <= 0) throw IllegalStateException("AudioRecord.read=$count")
                offset += count
                if (offset != pcm.size) continue
                val bars = FloatArray(6)
                if (!NativeDsp.analyze(pcm, bars)) throw IllegalStateException("Rust DSP rejected the PCM block")
                if (blocks++ == 0L || blocks % 24 == 0L) {
                    var peak = 0f
                    for (sample in pcm) peak = maxOf(peak, Math.abs(sample))
                    Log.i(TAG, "PCM block=$blocks uid=$uid peak=$peak bars=${bars.contentToString()}")
                }
                listener.onBars(bars)
                offset = 0
            }
        } catch (error: Throwable) {
            val cause = if (error is InvocationTargetException && error.cause != null) error.cause!! else error
            if (!stopped) {
                Log.e(TAG, "capture unavailable uid=$uid", cause)
                listener.onUnavailable(cause.javaClass.simpleName + ": " + cause.message)
            }
        } finally {
            val current = record
            record = null
            if (current != null) {
                try {
                    current.stop()
                } catch (ignored: IllegalStateException) {
                }
                current.release()
            }
            if (registered) {
                try {
                    AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass).invoke(manager, policy)
                } catch (error: ReflectiveOperationException) {
                    Log.e(TAG, "Cannot unregister audio policy", error)
                }
            }
            Log.i(TAG, "capture stopped uid=$uid")
        }
    }

    private companion object {
        const val TAG = "Hyperwave"
    }
}
