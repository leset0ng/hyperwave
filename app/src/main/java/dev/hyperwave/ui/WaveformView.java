package dev.hyperwave.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.util.Log;
import dev.hyperwave.audio.PlaybackCapture;

/** Six center-aligned bars. Audio updates animate geometry, not raw amplitudes. */
public final class WaveformView extends View implements Choreographer.FrameCallback {
    private static final PathInterpolator LIVE_CURVE = new PathInterpolator(0, 0, 0.58f, 1);
    private static final PathInterpolator PAUSE_CURVE = new PathInterpolator(0.33f, 1, 0.67f, 1);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix gradientMatrix = new Matrix();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float[] start = new float[18];
    private final float[] target = new float[18];
    private final float[] shown = new float[18];
    private long transitionStart;
    private long duration = 80;
    private long fallbackStart;
    private boolean playing;
    private boolean hostVisible = true;
    private boolean scheduled;
    private boolean unavailable;
    private int sourceUid = -1;
    private volatile int generation;
    private PlaybackCapture capture;
    private boolean reportedLive;
    private int topColor = Color.WHITE;
    private int bottomColor = Color.WHITE;
    private LinearGradient gradient;
    private boolean geometryReady;

    public WaveformView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
    }

    public void setColors(int top, int bottom) {
        if (topColor == top && bottomColor == bottom) return;
        topColor = top;
        bottomColor = bottom;
        gradient = new LinearGradient(0, 0, 0, 1, top, bottom, Shader.TileMode.CLAMP);
        invalidate();
    }

    public void configure(int uid, boolean isPlaying) {
        boolean changed = sourceUid != uid || playing != isPlaying;
        sourceUid = uid;
        playing = isPlaying;
        if (changed) {
            stopCapture();
            unavailable = false;
            reportedLive = false;
            if (!playing) animateTo(new float[6], 900);
            reconcileCapture();
        }
        invalidate();
    }

    public void setHostVisible(boolean visible) {
        hostVisible = visible;
        if (visible) reconcileCapture();
        else stopCapture();
    }

    private boolean active() {
        return hostVisible && isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
    }

    private void reconcileCapture() {
        if (!active()) return;
        scheduleFrame();
        if (!playing || capture != null) return;
        if (sourceUid < 0) {
            setUnavailable("No playback UID");
            return;
        }
        int token = ++generation;
        capture = new PlaybackCapture(getContext(), sourceUid, new PlaybackCapture.Listener() {
            @Override public void onBars(float[] values) { publish(token, values); }
            @Override public void onUnavailable(String reason) {
                main.post(() -> {
                    if (token == generation && active()) setUnavailable(reason);
                });
            }
        });
        capture.start();
    }

    private void publish(int token, float[] values) {
        main.post(() -> {
            if (token != generation || !active() || !playing) return;
            unavailable = false;
            if (!reportedLive) {
                reportedLive = true;
                Log.i("Hyperwave", "renderer live uid=" + sourceUid + " bars=" + java.util.Arrays.toString(values));
            }
            animateTo(values, 80);
        });
    }

    private void setUnavailable(String reason) {
        unavailable = true;
        fallbackStart = SystemClock.uptimeMillis();
        Log.w("Hyperwave", "waveform fallback: " + reason);
        scheduleFrame();
    }

    private void stopCapture() {
        generation++;
        if (capture != null) { capture.stop(); capture = null; }
        Choreographer.getInstance().removeFrameCallback(this);
        scheduled = false;
    }

    private void geometry(float[] values, float[] output) {
        float size = Math.min(getWidth(), getHeight());
        float base = size * 5f / 48f;
        for (int i = 0; i < 6; i++) {
            float value = Math.max(0, Math.min(1, values[i]));
            output[i * 3] = Math.max(base, value * size);
            output[i * 3 + 1] = base * (1 + 0.12f * value);
            output[i * 3 + 2] = Math.round(output[i * 3 + 1] / 2);
        }
    }

    private void animateTo(float[] values, long milliseconds) {
        long now = SystemClock.uptimeMillis();
        evaluate(now);
        System.arraycopy(shown, 0, start, 0, shown.length);
        geometry(values, target);
        transitionStart = now;
        duration = milliseconds;
        scheduleFrame();
    }

    private void evaluate(long now) {
        float fraction = Math.min(1, Math.max(0, (now - transitionStart) / (float) duration));
        float eased = (duration == 900 ? PAUSE_CURVE : LIVE_CURVE).getInterpolation(fraction);
        for (int i = 0; i < shown.length; i++) shown[i] = start[i] + (target[i] - start[i]) * eased;
    }

    private void scheduleFrame() {
        if (!scheduled && active()) {
            scheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override public void doFrame(long frameTimeNanos) {
        scheduled = false;
        if (!active()) return;
        if (playing && unavailable) {
            float[] values = FallbackFrames.sample((SystemClock.uptimeMillis() - fallbackStart) / 2660f);
            geometry(values, shown);
        } else evaluate(SystemClock.uptimeMillis());
        invalidate();
        if ((playing && unavailable) || SystemClock.uptimeMillis() - transitionStart < duration) scheduleFrame();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        geometry(new float[6], shown);
        System.arraycopy(shown, 0, start, 0, shown.length);
        System.arraycopy(shown, 0, target, 0, shown.length);
        geometryReady = true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!geometryReady) return;
        float size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2;
        float center = getHeight() / 2f;
        float base = size * 5 / 48;
        float slot = 1.12f * base;
        float pitch = slot + (size - 6 * slot) / 5;
        paint.setColor(Color.WHITE);
        paint.setShader(gradient);
        for (int i = 0; i < 6; i++) {
            float height = shown[i * 3];
            float width = shown[i * 3 + 1];
            float radius = shown[i * 3 + 2];
            float x = left + i * pitch + (slot - width) / 2;
            rect.set(x, center - height / 2, x + width, center + height / 2);
            if (gradient != null) {
                // Match the native Lottie fill: each bar spans the full gradient.
                gradientMatrix.setScale(1, height);
                gradientMatrix.postTranslate(0, rect.top);
                gradient.setLocalMatrix(gradientMatrix);
            }
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); reconcileCapture(); }
    @Override protected void onDetachedFromWindow() { stopCapture(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (main == null) return;
        if (isShown()) reconcileCapture(); else stopCapture();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (main == null) return;
        if (visibility == VISIBLE) reconcileCapture(); else stopCapture();
    }
}
