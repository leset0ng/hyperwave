package ng.lst.hyperwave.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
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
import ng.lst.hyperwave.MainActivity
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
        } finally {
            instrumentation.runOnMainSync {
                if (viewReady) view.dispose()
                if (stageReady) (stage.parent as? ViewGroup)?.removeView(stage)
                activity.finish()
            }
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
