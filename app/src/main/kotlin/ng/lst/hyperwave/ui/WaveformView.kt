package ng.lst.hyperwave.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.animation.PathInterpolator
import ng.lst.hyperwave.AppearanceSettings.Style
import ng.lst.hyperwave.audio.PlaybackCapture
import java.util.concurrent.ThreadLocalRandom

/** Audio-driven geometry with iOS, native HyperOS, and hybrid MiOS appearances. */
class WaveformView(context: Context) : View(context), Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val cornerBuilders = Array(6) { ContinuousCorners.Builder() }
    private val shaderMatrix = Matrix()
    private val main = Handler(Looper.getMainLooper())
    private val motion = GeometryTransition(
            { value -> LIVE_CURVE.getInterpolation(value.toFloat()).toDouble() },
            { value -> PAUSE_CURVE.getInterpolation(value.toFloat()).toDouble() },
    )
    private val geometry = FloatArray(GeometryTransition.COMPONENTS)
    private val values = FloatArray(6)
    private val fallbackValues = FloatArray(6)
    private val nativeGradientStart = FloatArray(5)
    private val nativeGradientEnd = FloatArray(5)
    private val silence = FloatArray(6)
    private val secondaryColor: Int
    private var fallbackStart = 0L
    private var fallbackVariant = 0
    private var playing = false
    private var hostVisible = true
    private var scheduled = false
    private var unavailable = false
    private var sourceUid = -1

    @Volatile
    private var generation = 0
    private var capture: PlaybackCapture? = null
    private var reportedLive = false
    private var topColor = Color.WHITE
    private var bottomColor = Color.WHITE
    private var gradient: LinearGradient? = null
    private var artworkShader: BitmapShader? = null
    private var artworkSize = 0
    private var geometryReady = false
    private var style = Style.HYPEROS
    private var nativeSize = 0f
    private var heightScale = 1f

    init {
        val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        secondaryColor = colors.getColor(0, Color.GRAY)
        colors.recycle()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun setStyle(next: Style, nextNativeSize: Float) {
        if (style == next && nativeSize == nextNativeSize) return
        val now = SystemClock.uptimeMillis()
        refreshShown(now)
        style = next
        nativeSize = nextNativeSize
        val levels = if (!playing) silence else if (unavailable) fallbackValues else values
        calculate(levels, unavailable, geometry)
        motion.transition(geometry, now, 80L)
        scheduleFrame()
        invalidate()
    }

    fun setHeightScale(next: Float) {
        if (heightScale == next) return
        val now = SystemClock.uptimeMillis()
        refreshShown(now)
        heightScale = next
        val levels = if (!playing) silence else if (unavailable) fallbackValues else values
        calculate(levels, unavailable, geometry)
        motion.transition(geometry, now, 80L)
        scheduleFrame()
        invalidate()
    }

    fun setArtwork(bitmap: Bitmap?) {
        artworkShader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        artworkSize = bitmap?.width ?: 0
        invalidate()
    }

    fun setColors(top: Int, bottom: Int) {
        if (topColor == top && bottomColor == bottom) return
        topColor = top
        bottomColor = bottom
        gradient = LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP)
        invalidate()
    }

    fun configure(uid: Int, isPlaying: Boolean) {
        val changed = sourceUid != uid || playing != isPlaying
        if (changed) {
            val now = SystemClock.uptimeMillis()
            refreshShown(now)
            stopCapture()
            if (sourceUid != uid) values.fill(0f)
            sourceUid = uid
            playing = isPlaying
            unavailable = false
            reportedLive = false
            calculate(if (playing) values else silence, false, geometry)
            motion.transition(geometry, now, if (playing) 80L else 900L)
            reconcileCapture()
        }
        invalidate()
    }

    fun setHostVisible(visible: Boolean) {
        hostVisible = visible
        if (visible) reconcileCapture() else stopCapture()
        invalidate()
    }

    private fun active(): Boolean =
            hostVisible && isAttachedToWindow && windowVisibility == VISIBLE && isShown

    private fun reconcileCapture() {
        if (!active()) return
        scheduleFrame()
        if (!playing || capture != null) return
        if (sourceUid < 0) {
            setUnavailable("No playback UID")
            return
        }
        val token = ++generation
        val listener = object : PlaybackCapture.Listener {
            override fun onBars(bars: FloatArray) {
                main.post {
                    if (token != generation || !active() || !playing) return@post
                    val now = SystemClock.uptimeMillis()
                    refreshShown(now)
                    unavailable = false
                    bars.copyInto(values, 0, 0, 6)
                    if (!reportedLive) {
                        reportedLive = true
                        Log.i("HyperWave", "renderer live uid=$sourceUid bars=${bars.contentToString()}")
                    }
                    calculate(values, false, geometry)
                    motion.transition(geometry, now, 80L)
                    scheduleFrame()
                }
            }

            override fun onUnavailable(reason: String) {
                main.post {
                    if (token == generation && active() && playing) setUnavailable(reason)
                }
            }
        }
        val started = PlaybackCapture(context, sourceUid, listener)
        capture = started
        started.start()
    }

    private fun setUnavailable(reason: String) {
        if (!unavailable) {
            fallbackStart = SystemClock.uptimeMillis()
            fallbackVariant = ThreadLocalRandom.current().nextInt(3)
        }
        unavailable = true
        Log.w("HyperWave", "waveform fallback: $reason")
        scheduleFrame()
    }

    private fun stopCapture() {
        generation++
        capture?.stop()
        capture = null
        Choreographer.getInstance().removeFrameCallback(this)
        scheduled = false
    }

    private fun calculate(levels: FloatArray, fallback: Boolean, output: FloatArray) {
        val size = minOf(width, height).toFloat()
        WaveformGeometry.calculate(style.usesSixBars, levels, size,
                fallback, playing, output, if (nativeSize > 0f) nativeSize else size, heightScale)
    }

    private fun refreshShown(now: Long) {
        if (playing && unavailable) {
            if (style.usesSixBars) {
                FallbackFrames.sample((now - fallbackStart) / 2660.0, fallbackVariant, fallbackValues)
            } else {
                HyperOsFallback.sample(now - fallbackStart, fallbackValues, nativeGradientStart, nativeGradientEnd)
            }
            calculate(fallbackValues, true, geometry)
            motion.display(geometry)
        } else motion.evaluate(now)
    }

    private fun scheduleFrame() {
        if (!scheduled && active()) {
            scheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!active()) return
        val now = SystemClock.uptimeMillis()
        refreshShown(now)
        invalidate()
        if ((playing && unavailable) || motion.animating(now)) scheduleFrame()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculate(if (playing) values else silence, false, geometry)
        motion.reset(geometry)
        geometryReady = true
        scheduleFrame()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!geometryReady || !hostVisible) return
        val size = minOf(width, height).toFloat()
        val left = (width - size) / 2
        val center = height / 2f
        val ios = style == Style.IOS
        val artwork = artworkShader
        val shader = gradient
        paint.color = if (ios && artwork == null) secondaryColor else Color.WHITE
        paint.shader = if (ios) artwork else shader
        if (ios && artwork != null) {
            shaderMatrix.setScale(size / artworkSize, size / artworkSize)
            shaderMatrix.postTranslate(left, center - size / 2)
            artwork.setLocalMatrix(shaderMatrix)
        }
        for (i in 0 until 6) {
            val offset = i * 4
            val barHeight = minOf(size, motion.shown[offset])
            val barWidth = motion.shown[offset + 1]
            val radius = motion.shown[offset + 2]
            if (barHeight <= 0 || barWidth <= 0) continue
            val x = left + motion.shown[offset + 3] - barWidth / 2
            rect.set(x, center - barHeight / 2, x + barWidth, center + barHeight / 2)
            if (!ios && shader != null) {
                if (style == Style.MIOS) {
                    // Map the native palette across each six-bar shape, reversing it on pause.
                    shaderMatrix.setScale(1f, if (playing) barHeight else -barHeight)
                    shaderMatrix.postTranslate(0f, if (playing) rect.top else rect.bottom)
                } else if (playing) {
                    val sourceHeight = motion.shown[offset] / heightScale
                    val level = maxOf(0f, minOf(1f, (sourceHeight * 66 / size - 10.962f) / 33.756f))
                    val scale = size / 66 * barHeight / sourceHeight
                    val half = 5.313f + (19.659f - 5.313f) * level
                    val start = if (unavailable && i < 5) nativeGradientStart[i] else -half
                    val end = if (unavailable && i < 5) nativeGradientEnd[i] else half
                    shaderMatrix.setScale(1f, (end - start) * scale)
                    shaderMatrix.postTranslate(0f, center + start * scale)
                } else {
                    shaderMatrix.setScale(1f, -12 * size / 66)
                    shaderMatrix.postTranslate(0f, center + 6 * size / 66)
                }
                shader.setLocalMatrix(shaderMatrix)
            }
            if (style.usesSixBars) {
                canvas.drawPath(cornerBuilders[i].build(rect, radius), paint)
            } else canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reconcileCapture()
    }

    override fun onDetachedFromWindow() {
        stopCapture()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) reconcileCapture() else stopCapture()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) reconcileCapture() else stopCapture()
    }

    private companion object {
        private val LIVE_CURVE = PathInterpolator(0f, 0f, 0.58f, 1f)
        private val PAUSE_CURVE = PathInterpolator(0.33f, 1f, 0.67f, 1f)
    }
}
