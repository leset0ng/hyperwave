package dev.hyperwave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/** Controlled capture-friendly media source; never reads a microphone or user file. */
class TestAudioService : Service() {
    private lateinit var session: MediaSession

    @Volatile
    private var running = false

    @Volatile
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Hyperwave test playback", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "HyperwaveTest")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPause() {
                stopSelf()
            }

            override fun onStop() {
                stopSelf()
            }
        })
        val artwork = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        paint.shader = LinearGradient(0f, 0f, 128f, 128f, Color.rgb(255, 80, 120),
                Color.rgb(70, 80, 230), Shader.TileMode.CLAMP)
        Canvas(artwork).drawRect(0f, 0f, 128f, 128f, paint)
        session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Six-band audio test")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Hyperwave")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 60000).build())
        session.setSessionActivity(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && STOP == intent.action) {
            stopSelf()
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this, 1,
                Intent(this, TestAudioService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Six-band audio test").setContentText("Hyperwave")
                .setContentIntent(session.controller.sessionActivity)
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0))
                .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build())
                .setOngoing(true).build()
        startForeground(1, notification)
        if (!running) {
            running = true
            session.setPlaybackState(PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f, SystemClock.elapsedRealtime()).build())
            session.isActive = true
            val thread = Thread({ play() }, "Hyperwave-test-audio")
            worker = thread
            thread.start()
        }
        return START_NOT_STICKY
    }

    private fun play() {
        var current: AudioTrack? = null
        try {
            val manager = getSystemService(AudioManager::class.java)
            manager.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build()
            val audio = AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(4800 * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
            current = audio
            track = audio
            if (!running) return
            audio.play()
            val samples = FloatArray(2400)
            var phase = 0.0
            var offset = 0L
            while (running && offset < 48000L * 60) {
                val band = (offset / 96000).toInt() % FREQUENCIES.size
                val frequency = FREQUENCIES[band]
                for (i in samples.indices) {
                    val envelope = 0.035 * (1 + Math.sin((offset + i) / 48000.0 * Math.PI))
                    samples[i] = (envelope * Math.sin(phase)).toFloat()
                    phase = (phase + 2 * Math.PI * frequency / 48000) % (2 * Math.PI)
                }
                var written = 0
                while (running && written < samples.size) {
                    val count = audio.write(samples, written, samples.size - written,
                            AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) throw IllegalStateException("AudioTrack.write=$count")
                    written += count
                }
                offset += samples.size
            }
        } catch (error: Throwable) {
            if (running) Log.e("Hyperwave", "test audio failed", error)
        } finally {
            track = null
            if (current != null) {
                try {
                    current.stop()
                } catch (ignored: IllegalStateException) {
                }
                current.release()
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        val current = track
        if (current != null) {
            try {
                current.pause()
                current.flush()
            } catch (ignored: IllegalStateException) {
            }
        }
        worker?.interrupt()
        session.setPlaybackState(PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
        session.isActive = false
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    companion object {
        const val PLAY = "dev.hyperwave.PLAY_TEST"
        private const val STOP = "dev.hyperwave.STOP_TEST"
        private const val CHANNEL = "hyperwave_test"
        private val FREQUENCIES = doubleArrayOf(45.0, 120.0, 320.0, 900.0, 2800.0, 6500.0)
    }
}
