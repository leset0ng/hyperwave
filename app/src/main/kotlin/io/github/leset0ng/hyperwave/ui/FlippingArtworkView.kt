package io.github.leset0ng.hyperwave.ui

import android.animation.ValueAnimator
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.widget.ImageView
import kotlin.math.cos
import kotlin.math.sin

/** A sibling of the native artwork; the original ImageView keeps receiving host updates. */
internal class FlippingArtworkView(private val original: ImageView) : View(original.context),
        Choreographer.FrameCallback {
    private val model = ArtworkFlipModel<Drawable>()
    private val node = RenderNode("HyperWave artwork blur")
    private val matrix = Matrix()
    private val projection = FloatArray(9)
    private val clip = Path()
    private val outline = Outline()
    private val outlineRect = Rect()
    private val oldBounds = Rect()
    private val content = RectF()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        blendMode = BlendMode.PLUS
    }
    private var latest: Drawable? = null
    private var seed: Any? = null
    private var hostVisible = true
    private var scheduled = false
    private var disposed = false
    private var clock = 0.0
    private var wallTime = SystemClock.uptimeMillis()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun update(drawable: Drawable?, identity: Any?) {
        latest = drawable?.constantState?.newDrawable(resources)?.mutate() ?: drawable
        seed = identity
        model.update(latest, identity, ratio(latest), now(), animate = canAnimate())
        invalidate()
        schedule()
    }

    fun setHostVisible(visible: Boolean) {
        hostVisible = visible
        if (!visible) settle()
        else { invalidate(); schedule() }
    }

    private fun settle() {
        cancelFrame()
        model.update(latest, seed, ratio(latest), now(), animate = false)
        node.setRenderEffect(null)
        invalidate()
    }

    fun dispose() {
        settle()
        disposed = true
        latest = null
        seed = null
        model.update(null, null, 1.0, now(), animate = false)
        node.discardDisplayList()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        schedule()
    }

    override fun onDetachedFromWindow() {
        settle()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) settle() else schedule()
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (disposed || !hostVisible || !isShown) return
        val time = now()
        if (!ValueAnimator.areAnimatorsEnabled()) settle() else model.advance(time)
        invalidate()
        schedule()
    }

    private fun schedule() {
        if (!scheduled && !disposed && hostVisible && isAttachedToWindow && isShown &&
                model.animating(clock)) {
            scheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun cancelFrame() {
        if (scheduled) Choreographer.getInstance().removeFrameCallback(this)
        scheduled = false
    }

    private fun now(): Double {
        val wall = SystemClock.uptimeMillis()
        val scale = Settings.Global.getFloat(context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        clock += (wall - wallTime).coerceAtLeast(0L) / 1000.0 /
                (if (scale.isFinite() && scale > 0f) scale else 1f)
        wallTime = wall
        return clock
    }

    private fun canAnimate(): Boolean = hostVisible && ValueAnimator.areAnimatorsEnabled()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawable = model.displayed ?: return
        if (width <= 0 || height <= 0) return
        val ratio = model.aspectRatio.toFloat()
        val w = minOf(width.toFloat(), height * ratio)
        val h = minOf(height.toFloat(), width / ratio)
        content.set((width - w) / 2f, (height - h) / 2f,
                (width + w) / 2f, (height + h) / 2f)
        original.outlineProvider?.getOutline(original, outline)
        val radius = if (outline.getRect(outlineRect)) outline.radius.coerceAtLeast(0f) else 0f
        clip.rewind()
        clip.addRoundRect(content, minOf(radius, w / 2f, h / 2f),
                minOf(radius, w / 2f, h / 2f), Path.Direction.CW)
        val save = canvas.save()
        // A centered Y rotation with perspective normalized by artwork width. SwiftUI's
        // dimensionless 0.1 is mapped to a projective matrix, never cameraDistance=0.1px.
        val radians = Math.toRadians(model.renderedAngle)
        val depth = (sin(radians) * 0.1 / w).toFloat()
        projection[0] = cos(radians).toFloat()
        projection[1] = 0f
        projection[2] = 0f
        projection[3] = 0f
        projection[4] = 1f
        projection[5] = 0f
        projection[6] = depth
        projection[7] = 0f
        projection[8] = 1f
        matrix.setValues(projection)
        matrix.preTranslate(-width / 2f, -height / 2f)
        matrix.postTranslate(width / 2f, height / 2f)
        canvas.concat(matrix)
        canvas.clipPath(clip)
        val layer = canvas.saveLayerAlpha(content, (model.opacity * 255).toInt())
        val blur = (model.reveal * 15f * resources.displayMetrics.density).toFloat()
        if (blur > 0.01f && canvas.isHardwareAccelerated) {
            node.setPosition(0, 0, width, height)
            node.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
            val recording = node.beginRecording()
            drawImage(recording, drawable)
            node.endRecording()
            canvas.drawRenderNode(node)
        } else {
            node.setRenderEffect(null)
            drawImage(canvas, drawable)
        }
        // The light is outside the blur, inside both the rounded clip and the 3D transform.
        if (model.reveal > 0.0) {
            glow.alpha = (0.3 * model.reveal * 255).toInt()
            canvas.drawRect(content, glow)
        }
        canvas.restoreToCount(layer)
        canvas.restoreToCount(save)
    }

    private fun drawImage(canvas: Canvas, drawable: Drawable) {
        oldBounds.set(drawable.bounds)
        drawable.setBounds(content.left.toInt(), content.top.toInt(),
                content.right.toInt(), content.bottom.toInt())
        drawable.draw(canvas)
        drawable.bounds = oldBounds
    }

    private fun ratio(drawable: Drawable?): Double = if (drawable != null &&
            drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        drawable.intrinsicWidth.toDouble() / drawable.intrinsicHeight else 1.0
}
