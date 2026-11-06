package io.github.leset0ng.hyperwave.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.leset0ng.hyperwave.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual hardware Canvas, RenderNode blur, outline and frame lifecycle. */
internal object ArtworkFlipDeviceCheck {
    fun verify(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
                MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var stage: FrameLayout
        lateinit var view: FlippingArtworkView
        var stageReady = false
        var viewReady = false
        try {
            instrumentation.runOnMainSync {
                val density = activity.resources.displayMetrics.density
                stage = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
                stageReady = true
                val original = ImageView(activity).apply {
                    setImageDrawable(ColorDrawable(Color.RED))
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, 12 * density)
                        }
                    }
                    clipToOutline = true
                    visibility = View.INVISIBLE
                }
                stage.addView(original, FrameLayout.LayoutParams((128 * density).toInt(),
                        (128 * density).toInt(), Gravity.CENTER))
                view = FlippingArtworkView(original)
                viewReady = true
                stage.addView(view, FrameLayout.LayoutParams(original.layoutParams as FrameLayout.LayoutParams))
                (activity.window.decorView as ViewGroup).addView(stage,
                        FrameLayout.LayoutParams((160 * density).toInt(), (160 * density).toInt(), Gravity.CENTER))
                view.update(ColorDrawable(Color.RED), "first")
            }
            frames(instrumentation, 350)
            val first = capture(instrumentation, activity, stage)
            try {
                check(first.getPixel(first.width / 2, first.height / 2) == Color.RED) { "first artwork missing" }
                check(first.getPixel(0, 0) == Color.BLACK) { "artwork escaped stage bounds" }
            } finally { first.recycle() }
            instrumentation.runOnMainSync { view.update(ColorDrawable(Color.BLUE), "second") }
            frames(instrumentation, 210)
            val turning = capture(instrumentation, activity, stage)
            try {
                val center = turning.getPixel(turning.width / 2, turning.height / 2)
                check(Color.blue(center) > Color.red(center)) { "pending image did not reveal on hardware Canvas" }
            } finally { turning.recycle() }
            // Retarget after reveal while the first spring is still moving.
            instrumentation.runOnMainSync { view.update(ColorDrawable(Color.GREEN), "third") }
            frames(instrumentation, 2100)
            val final = capture(instrumentation, activity, stage)
            try {
                check(final.getPixel(final.width / 2, final.height / 2) == Color.GREEN) { "latest artwork did not settle" }
                val inset = final.width / 10
                check(final.getPixel(inset, inset) == Color.BLACK) { "host rounded corner was lost" }
            } finally { final.recycle() }
            instrumentation.runOnMainSync {
                view.update(ColorDrawable(Color.BLUE), "fourth")
                view.setHostVisible(false)
                stage.removeView(view)
                stage.addView(view, FrameLayout.LayoutParams((128 * activity.resources.displayMetrics.density).toInt(),
                        (128 * activity.resources.displayMetrics.density).toInt(), Gravity.CENTER))
                view.setHostVisible(true)
            }
            frames(instrumentation, 50)
            val restored = capture(instrumentation, activity, stage)
            try {
                check(restored.getPixel(restored.width / 2, restored.height / 2) == Color.BLUE) {
                    "hidden or detached artwork did not finish at latest image"
                }
            } finally { restored.recycle() }
            frames(instrumentation, 100)
            instrumentation.runOnMainSync {
                check(!view.isDirty) { "restored artwork did not settle" }
                repeat(60) { view.setHostVisible(true) }
                check(!view.isDirty) { "unchanged visible artwork requested drawing" }
                view.setHostVisible(false)
            }
            frames(instrumentation, 100)
            instrumentation.runOnMainSync {
                check(!view.isDirty) { "hidden artwork did not settle" }
                repeat(60) { view.setHostVisible(false) }
                check(!view.isDirty) { "unchanged hidden artwork requested drawing" }
                view.dispose()
            }
            verifyPixels(instrumentation, activity, stage)
        } finally {
            instrumentation.runOnMainSync {
                if (viewReady) view.dispose()
                if (stageReady) (stage.parent as? ViewGroup)?.removeView(stage)
                activity.finish()
            }
        }
    }

    /** Compare deterministic frames against the original renderer on the same GPU. */
    private fun verifyPixels(instrumentation: Instrumentation, activity: Activity, stage: FrameLayout) {
        val size = (128 * activity.resources.displayMetrics.density).toInt()
        val source = Bitmap.createBitmap(67, 83, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setPixel(x, y, Color.argb((x * 17 + y * 11) % 256,
                    (x * 41) % 256, (y * 29) % 256, (x * 7 + y * 13) % 256))
        }
        val views = ArrayList<View>()
        try {
            instrumentation.runOnMainSync {
                stage.removeAllViews()
                stage.setBackgroundColor(Color.rgb(49, 71, 93))
                stage.layoutParams = (stage.layoutParams as FrameLayout.LayoutParams).apply {
                    width = size * 2
                }
                repeat(2) { index ->
                    val original = ImageView(activity).apply {
                        visibility = View.INVISIBLE
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setRoundRect(0, 0, view.width, view.height, size * 0.13f)
                            }
                        }
                    }
                    val params = FrameLayout.LayoutParams(size, size,
                            Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = index * size }
                    stage.addView(original, FrameLayout.LayoutParams(params))
                    val renderer = if (index == 0) FlippingArtworkView(original)
                            else ReferenceFlippingArtworkView(original)
                    views.add(renderer)
                    stage.addView(renderer, params)
                }
            }
            frames(instrumentation, 50)
            val models = ArrayList<Any>()
            instrumentation.runOnMainSync {
                for (renderer in views) {
                    renderer.javaClass.getDeclaredField("disposed").apply { isAccessible = true }
                            .setBoolean(renderer, true)
                    models.add(renderer.javaClass.getDeclaredField("model").apply {
                        isAccessible = true
                    }.get(renderer))
                }
            }
            val images = Array(2) { BitmapDrawable(activity.resources, source) }
            fun compare(label: String, drawables: Array<out android.graphics.drawable.Drawable>,
                        ratio: Double, angle: Double, opacity: Double, reveal: Double) {
                instrumentation.runOnMainSync {
                    for (index in views.indices) {
                        val model = models[index]
                        for ((name, value) in listOf("displayed" to drawables[index],
                                "aspectRatio" to ratio, "angle" to angle,
                                "opacity" to opacity, "reveal" to reveal)) {
                            model.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(model, value)
                        }
                        views[index].invalidate()
                    }
                }
                frames(instrumentation, 35)
                val image = capture(instrumentation, activity, stage)
                try {
                    val pixels = IntArray(image.width * image.height)
                    image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                    for (y in 0 until image.height) for (x in 0 until size) {
                        check(pixels[y * image.width + x] == pixels[y * image.width + size + x]) {
                            "artwork pixels differ: $label at ($x, $y)"
                        }
                    }
                } finally { image.recycle() }
            }
            for (ratio in doubleArrayOf(1.0, 0.65, 1.8)) {
                for (angle in doubleArrayOf(0.0, 37.0)) {
                    for (reveal in doubleArrayOf(1.0, 0.5, 0.0)) {
                        compare("ratio=$ratio angle=$angle reveal=$reveal", images,
                                ratio, angle, if (reveal == 0.5) 0.4 else 1.0, reveal)
                    }
                }
            }
            // Keep bounds and drawable stable across effect updates to exercise reuse.
            compare("cache primed", images, 1.0, 20.0, 1.0, 1.0)
            compare("cache reused", images, 1.0, 20.0, 1.0, 0.6)
            source.eraseColor(Color.argb(91, 182, 23, 217))
            compare("mutable bitmap", images, 1.0, 20.0, 1.0, 0.6)
            val colors = Array(2) { ColorDrawable(Color.RED) }
            compare("uncached drawable", colors, 1.0, -31.0, 0.5, 0.8)
            colors.forEach { it.color = Color.BLUE }
            compare("changed drawable", colors, 1.0, -31.0, 0.5, 0.8)
        } finally {
            instrumentation.runOnMainSync {
                for (renderer in views) {
                    when (renderer) {
                        is FlippingArtworkView -> renderer.dispose()
                        is ReferenceFlippingArtworkView -> renderer.dispose()
                    }
                }
                stage.removeAllViews()
            }
            source.recycle()
        }
    }

    private fun frames(instrumentation: Instrumentation, milliseconds: Long) {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            var start = 0L
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    if (start == 0L) start = time
                    if (time - start >= milliseconds * 1_000_000L) latch.countDown()
                    else Choreographer.getInstance().postFrameCallback(this)
                }
            })
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "frame callback timed out" }
    }

    private fun capture(instrumentation: Instrumentation, activity: Activity, stage: View): Bitmap {
        val latch = CountDownLatch(1)
        lateinit var bitmap: Bitmap
        var result = PixelCopy.ERROR_UNKNOWN
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            stage.getLocationInWindow(location)
            bitmap = Bitmap.createBitmap(stage.width, stage.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, Rect(location[0], location[1],
                    location[0] + stage.width, location[1] + stage.height), bitmap, {
                result = it
                latch.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        check(latch.await(10, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "hardware frame capture failed: $result" }
        return bitmap
    }
}
