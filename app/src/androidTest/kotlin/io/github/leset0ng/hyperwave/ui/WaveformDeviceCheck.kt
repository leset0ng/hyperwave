package io.github.leset0ng.hyperwave.ui

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import io.github.leset0ng.hyperwave.AppearanceSettings.Style
import io.github.leset0ng.hyperwave.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Exercises real View dirtiness and restoration without creating an AudioRecord. */
internal object WaveformDeviceCheck {
    fun verify(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
                MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var view: WaveformView? = null
        try {
            lateinit var stage: FrameLayout
            lateinit var waveform: WaveformView
            instrumentation.runOnMainSync {
                stage = FrameLayout(activity)
                waveform = WaveformView(activity)
                view = waveform
                val size = (22 * activity.resources.displayMetrics.density).toInt()
                stage.addView(waveform, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                activity.setContentView(stage)
            }
            for (style in Style.entries) {
                val nativeSize = 66 * activity.resources.displayMetrics.density
                instrumentation.runOnMainSync {
                    waveform.setStyle(style, nativeSize)
                    waveform.setHeightScale(1f)
                }
                settle(instrumentation, 350)
                instrumentation.runOnMainSync {
                    check(!waveform.isDirty && !scheduled(waveform)) { "$style did not settle" }
                    val generation = generation(waveform)
                    repeat(60) {
                        waveform.configure(-1, false)
                        waveform.setHostVisible(true)
                    }
                    check(!waveform.isDirty && !scheduled(waveform)) { "$style repeated pause update rendered" }
                    check(generation(waveform) == generation) { "$style repeated pause update restarted capture" }
                    // Emulate a settled live callback without invoking privileged capture services.
                    field(waveform, "playing").setBoolean(waveform, true)
                    try {
                        repeat(60) {
                            waveform.configure(-1, true)
                            waveform.setHostVisible(true)
                        }
                        check(!waveform.isDirty && !scheduled(waveform)) { "$style repeated live update rendered" }
                        check(generation(waveform) == generation) { "$style repeated live update restarted capture" }
                    } finally {
                        field(waveform, "playing").setBoolean(waveform, false)
                    }
                    waveform.setHeightScale(0.5f)
                }
                settle(instrumentation, 200)
                instrumentation.runOnMainSync {
                    waveform.setHeightScale(0.75f)
                    waveform.setStyle(if (style.usesSixBars) Style.HYPEROS else Style.IOS, nativeSize)
                    val motion = motion(waveform)
                    check(motion.animating(android.os.SystemClock.uptimeMillis())) { "$style transition did not start" }
                    val started = field(motion, "started").getLong(motion)
                    val shown = motion.shown.copyOf()
                    repeat(60) {
                        waveform.configure(-1, false)
                        waveform.setHostVisible(true)
                    }
                    check(field(motion, "started").getLong(motion) == started && motion.shown.contentEquals(shown)) {
                        "$style repeated host update interrupted presentation"
                    }
                    check(scheduled(waveform)) { "$style active transition stopped" }
                    waveform.setHostVisible(false)
                    waveform.setStyle(style, nativeSize)
                    // The style setter keeps its own refresh path while hidden.
                    check(!scheduled(waveform)) { "$style hidden appearance update scheduled a frame" }
                    val generation = generation(waveform)
                    repeat(60) { waveform.setHostVisible(false) }
                    check(generation(waveform) == generation && !scheduled(waveform)) {
                        "$style repeated hidden update touched capture or scheduled frames"
                    }
                }
                settle(instrumentation, 200)
                instrumentation.runOnMainSync { waveform.setHostVisible(true) }
                settle(instrumentation, 200)
                verifyGeometry(instrumentation, waveform, style, nativeSize, 0.75f)
                instrumentation.runOnMainSync {
                    stage.removeView(waveform)
                    stage.addView(waveform)
                }
                settle(instrumentation, 200)
                verifyGeometry(instrumentation, waveform, style, nativeSize, 0.75f)
                instrumentation.runOnMainSync { stage.visibility = View.INVISIBLE }
                settle(instrumentation, 50)
                instrumentation.runOnMainSync { stage.visibility = View.VISIBLE }
                settle(instrumentation, 200)
                verifyGeometry(instrumentation, waveform, style, nativeSize, 0.75f)
                instrumentation.runOnMainSync {
                    waveform.setColors(Color.RED, Color.BLUE)
                    check(waveform.isDirty) { "$style palette change did not invalidate" }
                    waveform.setColors(Color.WHITE, Color.WHITE)
                }
                settle(instrumentation, 100)
            }
            val artwork = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            try {
                instrumentation.runOnMainSync {
                    waveform.setArtwork(artwork)
                    check(waveform.isDirty) { "artwork change did not invalidate" }
                    waveform.setArtwork(null)
                }
            } finally {
                artwork.recycle()
            }
        } finally {
            instrumentation.runOnMainSync {
                view?.dispose()
                activity.finish()
            }
        }
    }

    private fun verifyGeometry(instrumentation: Instrumentation, view: WaveformView,
                               style: Style, nativeSize: Float, heightScale: Float) {
        instrumentation.runOnMainSync {
            val expected = FloatArray(GeometryTransition.COMPONENTS)
            WaveformGeometry.calculate(style.usesSixBars, FloatArray(6), minOf(view.width, view.height).toFloat(),
                    false, false, expected, nativeSize, heightScale)
            val actual = motion(view).shown
            check(actual.indices.all { abs(actual[it] - expected[it]) <= 0.00001f }) {
                "$style did not restore the paused endpoint"
            }
            check(!scheduled(view)) { "$style kept requesting frames after restoration" }
        }
    }

    private fun motion(view: WaveformView): GeometryTransition = field(view, "motion").get(view) as GeometryTransition
    private fun scheduled(view: WaveformView): Boolean = field(view, "scheduled").getBoolean(view)
    private fun generation(view: WaveformView): Int = field(view, "generation").getInt(view)
    private fun field(receiver: Any, name: String) = receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun settle(instrumentation: Instrumentation, milliseconds: Long) {
        CountDownLatch(1).await(milliseconds, TimeUnit.MILLISECONDS)
        instrumentation.waitForIdleSync()
    }
}
