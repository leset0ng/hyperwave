package io.github.leset0ng.hyperwave.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import io.github.leset0ng.hyperwave.MainActivity

/** Instrumentation-owned media source; no playback service ships in the application. */
internal class CaptureTestSource(private val context: Context) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val previousPolicy = manager.allowedCapturePolicy
    private var session: MediaSession? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var failure: Throwable? = null

    fun start(capturePolicy: Int, testFrequency: Double) {
        require(capturePolicy in AudioAttributes.ALLOW_CAPTURE_BY_ALL..AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        require(testFrequency == 0.0 || testFrequency in 20.0..20000.0)
        manager.setAllowedCapturePolicy(capturePolicy)
        val media = MediaSession(context, "HyperWaveTest")
        session = media
        val artwork = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 128f, 128f, Color.rgb(255, 80, 120),
                    Color.rgb(70, 80, 230), Shader.TileMode.CLAMP)
        }
        Canvas(artwork).drawRect(0f, 0f, 128f, 128f, paint)
        media.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Six-band audio test")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "HyperWave")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 27000).build())
        media.setSessionActivity(PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        media.setPlaybackState(PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f, SystemClock.elapsedRealtime()).build())
        media.isActive = true
        running = true
        worker = Thread({ play(capturePolicy, testFrequency) }, "HyperWave-test-audio").also { it.start() }
    }

    private fun play(capturePolicy: Int, testFrequency: Double) {
        var audio: AudioTrack? = null
        try {
            audio = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(capturePolicy).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(4800 * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
            track = audio
            if (!running) return
            audio.play()
            Log.i("HyperWave", "test playback policy=$capturePolicy 48000Hz float32 frequency=$testFrequency")
            val frequencies = doubleArrayOf(45.0, 120.0, 320.0, 900.0, 2800.0, 6500.0)
            val samples = FloatArray(2400)
            var phase = 0.0
            var offset = 0L
            while (running) {
                val frequency = if (testFrequency > 0.0) testFrequency
                        else frequencies[(offset / 96000).toInt() % frequencies.size]
                for (i in samples.indices) {
                    val envelope = 0.035 * (1 + Math.sin((offset + i) / 48000.0 * Math.PI))
                    samples[i] = (envelope * Math.sin(phase)).toFloat()
                    phase = (phase + 2 * Math.PI * frequency / 48000) % (2 * Math.PI)
                }
                var written = 0
                while (running && written < samples.size) {
                    val count = audio.write(samples, written, samples.size - written, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) throw IllegalStateException("AudioTrack.write=$count")
                    written += count
                }
                offset += samples.size
            }
        } catch (error: Throwable) {
            if (running) failure = error
        } finally {
            track = null
            audio?.release()
        }
    }

    override fun close() {
        running = false
        try {
            track?.let {
                try {
                    it.pause()
                    it.flush()
                } catch (ignored: IllegalStateException) {
                }
            }
            worker?.join(5000)
            check(worker?.isAlive != true) { "Test audio did not stop" }
        } finally {
            session?.release()
            session = null
            manager.setAllowedCapturePolicy(previousPolicy)
        }
        failure?.let { throw IllegalStateException("Test audio failed", it) }
    }
}
