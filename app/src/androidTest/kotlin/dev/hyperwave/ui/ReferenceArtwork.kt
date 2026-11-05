package dev.hyperwave.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** Frozen reference artwork pipeline used to check the native implementation on Android. */
internal object ReferenceArtwork {
    private const val SIZE = 96

    fun prepare(drawable: Drawable, logicalSize: Float): Bitmap {
        var source = (drawable as BitmapDrawable).bitmap
        val owned = source.config == Bitmap.Config.HARDWARE
        if (owned) source = source.copy(Bitmap.Config.ARGB_8888, false)
        try {
            var brightness = 0.0
            val row = IntArray(source.width)
            for (y in 0 until source.height) {
                source.getPixels(row, 0, row.size, 0, y, row.size, 1)
                for (color in row) {
                    val r = Color.red(color) / 255.0
                    val g = Color.green(color) / 255.0
                    val b = Color.blue(color) / 255.0
                    brightness += Math.sqrt(0.299 * r * r + 0.587 * g * g + 0.114 * b * b)
                }
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
            val pixels = IntArray(SIZE * SIZE)
            val scratch = IntArray(pixels.size)
            result.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            val radius = maxOf(1, Math.round(6f / logicalSize * SIZE))
            for (pass in 0 until 3) {
                box(pixels, scratch, radius, false)
                box(scratch, pixels, radius, true)
            }
            result.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            return result
        } finally {
            if (owned) source.recycle()
        }
    }

    private fun box(source: IntArray, target: IntArray, radius: Int, vertical: Boolean) {
        val count = 2 * radius + 1
        for (line in 0 until SIZE) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (position in -radius..radius) {
                val index = maxOf(0, minOf(SIZE - 1, position))
                val color = source[if (vertical) index * SIZE + line else line * SIZE + index]
                a += Color.alpha(color)
                r += Color.red(color)
                g += Color.green(color)
                b += Color.blue(color)
            }
            for (position in 0 until SIZE) {
                target[if (vertical) position * SIZE + line else line * SIZE + position] =
                        Color.argb(a / count, r / count, g / count, b / count)
                val remove = maxOf(0, position - radius)
                val add = minOf(SIZE - 1, position + radius + 1)
                val oldColor = source[if (vertical) remove * SIZE + line else line * SIZE + remove]
                val newColor = source[if (vertical) add * SIZE + line else line * SIZE + add]
                a += Color.alpha(newColor) - Color.alpha(oldColor)
                r += Color.red(newColor) - Color.red(oldColor)
                g += Color.green(newColor) - Color.green(oldColor)
                b += Color.blue(newColor) - Color.blue(oldColor)
            }
        }
    }
}
