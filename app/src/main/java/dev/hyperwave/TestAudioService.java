package dev.hyperwave;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/** Controlled capture-friendly media source; never reads a microphone or user file. */
public final class TestAudioService extends Service {
    public static final String PLAY = "dev.hyperwave.PLAY_TEST";
    private static final String STOP = "dev.hyperwave.STOP_TEST";
    private static final String CHANNEL = "hyperwave_test";
    private static final double[] FREQUENCIES = {45, 120, 320, 900, 2800, 6500};
    private MediaSession session;
    private volatile boolean running;
    private volatile AudioTrack track;
    private Thread worker;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Hyperwave test playback", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "HyperwaveTest");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPause() { stopSelf(); }
            @Override public void onStop() { stopSelf(); }
        });
        Bitmap artwork = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(0, 0, 128, 128, Color.rgb(255, 80, 120), Color.rgb(70, 80, 230), Shader.TileMode.CLAMP));
        new Canvas(artwork).drawRect(0, 0, 128, 128, paint);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Six-band audio test")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Hyperwave")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 60000).build());
        session.setSessionActivity(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TestAudioService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Six-band audio test").setContentText("Hyperwave")
                .setContentIntent(session.getController().getSessionActivity())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0))
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build())
                .setOngoing(true).build();
        startForeground(1, notification);
        if (!running) {
            running = true;
            session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1, SystemClock.elapsedRealtime()).build());
            session.setActive(true);
            worker = new Thread(this::play, "Hyperwave-test-audio");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void play() {
        AudioTrack current = null;
        try {
            AudioManager manager = getSystemService(AudioManager.class);
            manager.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL);
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build();
            current = new AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(4800 * 4).setTransferMode(AudioTrack.MODE_STREAM).build();
            track = current;
            if (!running) return;
            current.play();
            float[] samples = new float[2400];
            double phase = 0;
            for (long offset = 0; running && offset < 48000L * 60; offset += samples.length) {
                int band = (int) (offset / 96000) % FREQUENCIES.length;
                double frequency = FREQUENCIES[band];
                for (int i = 0; i < samples.length; i++) {
                    double envelope = .035 * (1 + Math.sin((offset + i) / 48000.0 * Math.PI));
                    samples[i] = (float) (envelope * Math.sin(phase));
                    phase = (phase + 2 * Math.PI * frequency / 48000) % (2 * Math.PI);
                }
                int written = 0;
                while (running && written < samples.length) {
                    int count = current.write(samples, written, samples.length - written, AudioTrack.WRITE_BLOCKING);
                    if (count <= 0) throw new IllegalStateException("AudioTrack.write=" + count);
                    written += count;
                }
            }
        } catch (Throwable error) {
            if (running) Log.e("Hyperwave", "test audio failed", error);
        } finally {
            track = null;
            if (current != null) {
                try { current.stop(); } catch (IllegalStateException ignored) {}
                current.release();
            }
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        running = false;
        AudioTrack current = track;
        if (current != null) { try { current.pause(); current.flush(); } catch (IllegalStateException ignored) {} }
        if (worker != null) worker.interrupt();
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0).build());
        session.setActive(false);
        session.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
