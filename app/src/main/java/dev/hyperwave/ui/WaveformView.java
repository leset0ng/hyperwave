package dev.hyperwave.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
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
import dev.hyperwave.AppearanceSettings.Style;
import dev.hyperwave.audio.PlaybackCapture;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/** Audio-driven geometry with separate iOS and native HyperOS appearances. */
public final class WaveformView extends View implements Choreographer.FrameCallback {
    private static final PathInterpolator LIVE_CURVE = new PathInterpolator(0, 0, .58f, 1);
    private static final PathInterpolator PAUSE_CURVE = new PathInterpolator(.33f, 1, .67f, 1);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final ContinuousCorners.Builder[] cornerBuilders = {
            new ContinuousCorners.Builder(), new ContinuousCorners.Builder(),
            new ContinuousCorners.Builder(), new ContinuousCorners.Builder(),
            new ContinuousCorners.Builder(), new ContinuousCorners.Builder()
    };
    private final Matrix shaderMatrix = new Matrix();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final GeometryTransition motion = new GeometryTransition(
            value -> LIVE_CURVE.getInterpolation((float) value),
            value -> PAUSE_CURVE.getInterpolation((float) value));
    private final float[] geometry = new float[GeometryTransition.COMPONENTS];
    private final float[] values = new float[6];
    private final float[] fallbackValues = new float[6];
    private final float[] nativeGradientStart = new float[5];
    private final float[] nativeGradientEnd = new float[5];
    private final float[] silence = new float[6];
    private long fallbackStart;
    private int fallbackVariant;
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
    private BitmapShader artworkShader;
    private int artworkSize;
    private final int secondaryColor;
    private boolean geometryReady;
    private Style style = Style.HYPEROS;

    public WaveformView(Context context) {
        super(context);
        TypedArray colors = context.obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
        secondaryColor = colors.getColor(0, Color.GRAY);
        colors.recycle();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
    }

    public void setStyle(Style next) {
        if (style == next) return;
        long now = SystemClock.uptimeMillis();
        refreshShown(now);
        style = next;
        calculate(playing ? values : silence, unavailable, geometry);
        motion.transition(geometry, now, 80);
        scheduleFrame();
        invalidate();
    }

    public void setArtwork(Bitmap bitmap) {
        artworkShader = bitmap == null ? null : new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        artworkSize = bitmap == null ? 0 : bitmap.getWidth();
        invalidate();
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
        if (changed) {
            long now = SystemClock.uptimeMillis();
            refreshShown(now);
            stopCapture();
            if (sourceUid != uid) Arrays.fill(values, 0);
            sourceUid = uid;
            playing = isPlaying;
            unavailable = false;
            reportedLive = false;
            calculate(playing ? values : silence, false, geometry);
            motion.transition(geometry, now, playing ? 80 : 900);
            reconcileCapture();
        }
        invalidate();
    }

    public void setHostVisible(boolean visible) {
        hostVisible = visible;
        if (visible) reconcileCapture(); else stopCapture();
        invalidate();
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
            @Override public void onBars(float[] bars) {
                main.post(() -> {
                    if (token != generation || !active() || !playing) return;
                    long now = SystemClock.uptimeMillis();
                    refreshShown(now);
                    unavailable = false;
                    System.arraycopy(bars, 0, values, 0, 6);
                    if (!reportedLive) {
                        reportedLive = true;
                        Log.i("Hyperwave", "renderer live uid=" + sourceUid + " bars=" + Arrays.toString(bars));
                    }
                    calculate(values, false, geometry);
                    motion.transition(geometry, now, 80);
                    scheduleFrame();
                });
            }
            @Override public void onUnavailable(String reason) {
                main.post(() -> {
                    if (token == generation && active() && playing) setUnavailable(reason);
                });
            }
        });
        capture.start();
    }

    private void setUnavailable(String reason) {
        if (!unavailable) {
            fallbackStart = SystemClock.uptimeMillis();
            fallbackVariant = ThreadLocalRandom.current().nextInt(3);
        }
        unavailable = true;
        Log.w("Hyperwave", "waveform fallback: " + reason);
        scheduleFrame();
    }

    private void stopCapture() {
        generation++;
        if (capture != null) { capture.stop(); capture = null; }
        Choreographer.getInstance().removeFrameCallback(this);
        scheduled = false;
    }

    private void calculate(float[] levels, boolean fallback, float[] output) {
        WaveformGeometry.calculate(style == Style.IOS, levels, Math.min(getWidth(), getHeight()), fallback, playing, output);
    }

    private void refreshShown(long now) {
        if (playing && unavailable) {
            if (style == Style.IOS) FallbackFrames.sample((now - fallbackStart) / 2660.0, fallbackVariant, fallbackValues);
            else HyperOsFallback.sample(now - fallbackStart, fallbackValues, nativeGradientStart, nativeGradientEnd);
            calculate(fallbackValues, true, geometry);
            motion.display(geometry);
        } else motion.evaluate(now);
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
        long now = SystemClock.uptimeMillis();
        refreshShown(now);
        invalidate();
        if ((playing && unavailable) || motion.animating(now)) scheduleFrame();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        calculate(playing ? values : silence, false, geometry);
        motion.reset(geometry);
        geometryReady = true;
        scheduleFrame();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!geometryReady || !hostVisible) return;
        float size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2;
        float center = getHeight() / 2f;
        boolean ios = style == Style.IOS;
        paint.setColor(ios && artworkShader == null ? secondaryColor : Color.WHITE);
        paint.setShader(ios ? artworkShader : gradient);
        if (ios && artworkShader != null) {
            shaderMatrix.setScale(size / artworkSize, size / artworkSize);
            shaderMatrix.postTranslate(left, center - size / 2);
            artworkShader.setLocalMatrix(shaderMatrix);
        }
        for (int i = 0; i < 6; i++) {
            int offset = i * 4;
            float height = motion.shown[offset];
            float width = motion.shown[offset + 1];
            float radius = motion.shown[offset + 2];
            if (height <= 0 || width <= 0) continue;
            float x = left + motion.shown[offset + 3] - width / 2;
            rect.set(x, center - height / 2, x + width, center + height / 2);
            if (!ios && gradient != null) {
                if (playing) {
                    float level = Math.max(0, Math.min(1, (height * 66 / size - 10.962f) / 33.756f));
                    float half = 5.313f + (19.659f - 5.313f) * level;
                    float start = unavailable && i < 5 ? nativeGradientStart[i] : -half;
                    float end = unavailable && i < 5 ? nativeGradientEnd[i] : half;
                    shaderMatrix.setScale(1, (end - start) * size / 66);
                    shaderMatrix.postTranslate(0, center + start * size / 66);
                } else {
                    shaderMatrix.setScale(1, -12 * size / 66);
                    shaderMatrix.postTranslate(0, center + 6 * size / 66);
                }
                gradient.setLocalMatrix(shaderMatrix);
            }
            if (ios) {
                canvas.drawPath(cornerBuilders[i].build(rect, radius), paint);
            } else canvas.drawRoundRect(rect, radius, radius, paint);
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
