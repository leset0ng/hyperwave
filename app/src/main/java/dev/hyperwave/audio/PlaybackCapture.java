package dev.hyperwave.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;

/** A UID-filtered playback copy. This never redirects the original output. */
public final class PlaybackCapture implements Runnable {
    public interface Listener {
        void onBars(float[] bars);
        void onUnavailable(String reason);
    }
    private static final String TAG = "Hyperwave";
    private final Context context;
    private final int uid;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile AudioRecord record;
    private final Thread thread;

    public PlaybackCapture(Context context, int uid, Listener listener) {
        this.context = context;
        this.uid = uid;
        this.listener = listener;
        this.thread = new Thread(this, "Hyperwave-capture-" + uid);
    }
    public void start() { thread.start(); }
    public void stop() {
        stopped = true;
        AudioRecord current = record;
        if (current != null) {
            try { current.stop(); } catch (IllegalStateException ignored) {}
        }
        thread.interrupt();
    }

    @Override public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioManager manager = context.getSystemService(AudioManager.class);
        Object policy = null;
        Class<?> policyClass = null;
        boolean registered = false;
        try {
            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
            ruleBuilderClass.getMethod("setTargetMixRole", int.class).invoke(ruleBuilder, 0);
            ruleBuilderClass.getMethod("addMixRule", int.class, Object.class).invoke(ruleBuilder, 4, Integer.valueOf(uid));
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
            ruleBuilderClass.getMethod("addRule", AudioAttributes.class, int.class).invoke(ruleBuilder, attributes, 1);
            // Privileged=true restricts capture to <=16kHz PCM16. Keep the 48kHz
            // path explicit, with normal playback capture policy restrictions.
            ruleBuilderClass.getMethod("allowPrivilegedPlaybackCapture", boolean.class).invoke(ruleBuilder, false);
            Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
            AudioFormat format = new AudioFormat.Builder().setSampleRate(48000)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
            mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, 3);
            Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

            policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(context);
            policyBuilderClass.getMethod("setLooper", Looper.class).invoke(policyBuilder, Looper.getMainLooper());
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
            policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);
            if (stopped) return;
            int result = (int) AudioManager.class.getMethod("registerAudioPolicy", policyClass).invoke(manager, policy);
            if (result != 0) throw new IllegalStateException("registerAudioPolicy=" + result);
            registered = true;
            AudioRecord current = (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
            record = current;
            if (current == null || current.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("Playback AudioRecord not initialized");
            if (current.getSampleRate() != 48000 || current.getChannelCount() != 1
                    || current.getAudioFormat() != AudioFormat.ENCODING_PCM_FLOAT)
                throw new IllegalStateException("Unexpected playback capture format");
            if (stopped) return;
            current.startRecording();
            if (current.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Playback AudioRecord did not start");
            Log.i(TAG, "capture started uid=" + uid + " 48000Hz mono float32");
            float[] pcm = new float[4096];
            int offset = 0;
            long blocks = 0;
            while (!stopped) {
                int count = current.read(pcm, offset, pcm.length - offset, AudioRecord.READ_BLOCKING);
                if (stopped) break;
                if (count <= 0) throw new IllegalStateException("AudioRecord.read=" + count);
                offset += count;
                if (offset != pcm.length) continue;
                float[] bars = new float[6];
                if (!NativeDsp.analyze(pcm, bars)) throw new IllegalStateException("Rust DSP rejected the PCM block");
                if (blocks++ == 0 || blocks % 24 == 0) {
                    float peak = 0;
                    for (float sample : pcm) peak = Math.max(peak, Math.abs(sample));
                    Log.i(TAG, "PCM block=" + blocks + " uid=" + uid + " peak=" + peak + " bars=" + java.util.Arrays.toString(bars));
                }
                listener.onBars(bars);
                offset = 0;
            }
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
            if (!stopped) {
                Log.e(TAG, "capture unavailable uid=" + uid, cause);
                listener.onUnavailable(cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        } finally {
            AudioRecord current = record;
            record = null;
            if (current != null) {
                try { current.stop(); } catch (IllegalStateException ignored) {}
                current.release();
            }
            if (registered) {
                try { AudioManager.class.getMethod("unregisterAudioPolicy", policyClass).invoke(manager, policy); }
                catch (ReflectiveOperationException error) { Log.e(TAG, "Cannot unregister audio policy", error); }
            }
            Log.i(TAG, "capture stopped uid=" + uid);
        }
    }
}
