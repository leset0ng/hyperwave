package ng.lst.hyperwave.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import ng.lst.hyperwave.audio.NativeDsp

/** Artwork treatment in the recovered order; blur is a separable Gaussian approximation. */
object ArtworkBackdrop {
    private const val SIZE = 96
    private val PIXEL_BUFFERS: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(64 * 1024) }

    fun prepare(drawable: Drawable, logicalSize: Float): Bitmap? {
        require(logicalSize > 0 && logicalSize.isFinite()) { "Expected a finite positive logical size" }
        var source: Bitmap?
        var owned: Boolean
        if (drawable is BitmapDrawable) {
            source = drawable.bitmap
            if (source == null || source.isRecycled) return null
            owned = source.config == Bitmap.Config.HARDWARE
            if (owned) source = source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            val width = maxOf(1, drawable.intrinsicWidth)
            val height = maxOf(1, drawable.intrinsicHeight)
            val scale = minOf(1f, 1024f / maxOf(width, height))
            source = Bitmap.createBitmap(maxOf(1, Math.round(width * scale)),
                    maxOf(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888)
            owned = true
            val previousBounds = drawable.copyBounds()
            try {
                drawable.setBounds(0, 0, source.width, source.height)
                drawable.draw(Canvas(source))
            } finally {
                drawable.setBounds(previousBounds)
            }
        }
        if (source == null) return null
        try {
            return prepare(source, logicalSize)
        } finally {
            if (owned) source.recycle()
        }
    }

    private fun prepare(source: Bitmap, logicalSize: Float): Bitmap {
        var pixels = PIXEL_BUFFERS.get() ?: IntArray(64 * 1024)
        if (pixels.size < source.width) {
            pixels = IntArray(source.width)
            PIXEL_BUFFERS.set(pixels)
        }
        var brightness = 0.0
        val rowsPerTile = pixels.size / source.width
        var y = 0
        while (y < source.height) {
            val rows = minOf(rowsPerTile, source.height - y)
            source.getPixels(pixels, 0, source.width, 0, y, source.width, rows)
            brightness = NativeDsp.sumArtworkBrightness(pixels, source.width * rows, brightness)
            if (brightness < 0) throw IllegalStateException("Rust rejected the artwork pixels")
            y += rowsPerTile
        }
        brightness /= source.width.toDouble() * source.height
        val lightening = maxOf(0.0, 0.6 - brightness).toFloat()
        val darkening = maxOf(0.0, brightness - 0.7).toFloat()
        val saturation = ColorMatrix()
        saturation.setSaturation(1.25f + 4 * lightening)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(saturation)
        val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        canvas.drawBitmap(source, Rect(left, top, left + side, top + side), Rect(0, 0, SIZE, SIZE), paint)
        canvas.drawColor(Color.argb(Math.round(255 * darkening), 0, 0, 0))
        canvas.drawColor(Color.argb(Math.round(255 * lightening), 255, 255, 255))
        result.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val radius = minOf(SIZE, maxOf(1, Math.round(6f / logicalSize * SIZE)))
        if (!NativeDsp.blurArtwork(pixels, radius)) throw IllegalStateException("Rust rejected the artwork blur")
        result.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return result
    }
}
