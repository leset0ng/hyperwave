package io.github.leset0ng.hyperwave.ui

import android.app.Activity
import android.app.Instrumentation
import android.media.AudioAttributes
import android.media.AudioFormat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.leset0ng.hyperwave.audio.NativeDsp
import io.github.leset0ng.hyperwave.audio.NativeReloadCheck
import io.github.leset0ng.hyperwave.module.ArtworkHooksDeviceCheck
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Random
import java.util.concurrent.atomic.AtomicReference

/** Dependency-free device regression and paired, warmed performance measurements. */
class PerformanceInstrumentation : Instrumentation() {
    private val results = Bundle()
    private var captureArguments: Bundle? = null
    private var nativeReload = false
    private var artworkFlip = false
    private var waveformLifecycle = false
    private var artworkHooks = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        captureArguments = arguments?.takeIf { it.containsKey("capture_policy") }
        nativeReload = arguments?.getString("native_reload") == "true"
        artworkFlip = arguments?.getString("artwork_flip") == "true"
        waveformLifecycle = arguments?.getString("waveform_lifecycle") == "true"
        artworkHooks = arguments?.getString("artwork_hooks") == "true"
        start()
    }

    override fun onStart() {
        try {
            if (artworkHooks) {
                ArtworkHooksDeviceCheck.verify(this)
                results.putString("checks", "PASS: fresh session metadata, token reuse/switch, disabled/re-enabled identity, concurrent cache, retirement and layout equality")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (waveformLifecycle) {
                WaveformDeviceCheck.verify(this)
                results.putString("checks", "PASS: three waveform styles, unchanged host updates, active transition, hide/show, detach, palette and artwork")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (artworkFlip) {
                ArtworkFlipDeviceCheck.verify(this)
                results.putString("checks", "PASS: artwork flip hardware reveal, latest image, host corners, hide/detach lifecycle")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (nativeReload) {
                NativeReloadCheck.verify(targetContext)
                results.putString("checks", "PASS: three native reload generations, JNI calls, copy cleanup")
                finish(Activity.RESULT_OK, results)
                return
            }
            val capture = captureArguments
            if (capture != null) {
                runCaptureSource(capture)
                finish(Activity.RESULT_OK, results)
                return
            }
            NativeDsp.load(targetContext.applicationInfo.nativeLibraryDir)
            verifyNativeBounds()
            verifyArtwork()
            verifyCorners()
            verifyConcurrentAudio()
            benchmarkArtwork()
            benchmarkCorners()
            results.putString("checks",
                    "PASS: artwork pixels, hardware copies, JNI bounds, corner paths, concurrent audio")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("failure", Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun runCaptureSource(arguments: Bundle) {
        // An unrelated process must still reject full-rate privileged formats.
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val format = AudioFormat.Builder().setSampleRate(48000)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val formatLimit = try {
            val error = mixClass.getMethod("canBeUsedForPrivilegedMediaCapture", AudioFormat::class.java)
                    .invoke(null, format)
            require(error is String, "format restriction escaped into an unrelated process")
            "PASS: format rejected"
        } catch (expected: NoSuchMethodException) {
            "Hidden API inaccessible; direct format check not exercised"
        }
        val policy = arguments.getString("capture_policy")!!.toInt()
        require(policy in AudioAttributes.ALLOW_CAPTURE_BY_ALL..AudioAttributes.ALLOW_CAPTURE_BY_NONE,
                "invalid capture policy")
        CaptureTestSource(targetContext).use { source ->
            source.start(policy, arguments.getString("test_frequency", "0").toDouble())
            CountDownLatch(1).await(27, TimeUnit.SECONDS)
            results.putString("capture_source", "policy=$policy; inspect SystemUI PCM logs for capture result")
            results.putString("unrelated_process_format_limit", formatLimit)
        }
    }

    private fun verifyNativeBounds() {
        val pixels = IntArray(96 * 96 + 1)
        pixels[pixels.size - 1] = 0x12345678
        require(NativeDsp.sumArtworkBrightness(pixels, 0, 0.0) == -1.0, "zero pixel count")
        require(NativeDsp.sumArtworkBrightness(pixels, pixels.size + 1, 0.0) == -1.0, "pixel count overflow")
        require(NativeDsp.sumArtworkBrightness(pixels, 1, Double.NaN) == -1.0, "nonfinite sum")
        require(!NativeDsp.blurArtwork(IntArray(1), 26), "short blur input")
        require(!NativeDsp.blurArtwork(pixels, 0) && !NativeDsp.blurArtwork(pixels, 97), "invalid blur radius")
        require(NativeDsp.blurArtwork(pixels, 26), "valid blur input")
        require(pixels[pixels.size - 1] == 0x12345678, "blur wrote beyond image")
        require(!NativeDsp.analyze(FloatArray(2), FloatArray(6)), "short PCM input")
    }

    private fun verifyArtwork() {
        for (dimensions in arrayOf(intArrayOf(1, 1), intArrayOf(127, 191), intArrayOf(1024, 768))) {
            val source = image(dimensions[0], dimensions[1])
            try {
                compareArtwork(source)
                val hardware = source.copy(Bitmap.Config.HARDWARE, false)
                require(hardware != null, "hardware bitmap copy")
                try {
                    compareArtwork(hardware)
                } finally {
                    hardware.recycle()
                }
                require(!source.isRecycled, "shared source was recycled")
            } finally {
                source.recycle()
            }
        }
        val colors = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0x7f444444, 0xffcccccc.toInt())
        for (color in colors) {
            val source = Bitmap.createBitmap(99, 101, Bitmap.Config.ARGB_8888)
            source.eraseColor(color)
            try {
                compareArtwork(source)
            } finally {
                source.recycle()
            }
        }
        val drawable = ColorDrawable(0x7f334455).apply { setBounds(2, 3, 12, 17) }
        val bounds = drawable.copyBounds()
        val raster = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, 1, 1)
            drawable.draw(Canvas(raster))
            drawable.bounds = bounds
            compareSharedArtwork(drawable, BitmapDrawable(targetContext.resources, raster))
            require(drawable.bounds == bounds, "shared preparation changed drawable bounds")
        } finally {
            drawable.bounds = bounds
            raster.recycle()
        }
    }

    private fun compareArtwork(source: Bitmap) {
        val drawable = BitmapDrawable(targetContext.resources, source)
        for (size in floatArrayOf(16f, 22f)) {
            val expected = ReferenceArtwork.prepare(drawable, size)
            val actual = ArtworkBackdrop.prepare(drawable, size)
            try {
                require(actual != null && expected.sameAs(actual), "artwork pixel mismatch at size $size")
            } finally {
                expected.recycle()
                actual?.recycle()
            }
            require(!source.isRecycled, "caller-owned bitmap was recycled")
        }
        compareSharedArtwork(drawable)
        val disposable = source.copy(Bitmap.Config.ARGB_8888, true)!!
        val base = ArtworkBackdrop.prepareBase(BitmapDrawable(targetContext.resources, disposable))!!
        disposable.recycle()
        val expected = ReferenceArtwork.prepare(drawable, 22f)
        val actual = base.render(22f)
        try {
            require(expected.sameAs(actual), "base retained caller-owned source pixels")
        } finally {
            expected.recycle()
            actual.recycle()
        }
    }

    private fun compareSharedArtwork(drawable: Drawable, reference: Drawable = drawable) {
        for (order in arrayOf(floatArrayOf(16f, 22f, 16f), floatArrayOf(22f, 16f, 22f))) {
            val base = ArtworkBackdrop.prepareBase(drawable)
            require(base != null, "shared artwork base missing")
            for (size in order) {
                val expected = ReferenceArtwork.prepare(reference, size)
                val actual = base!!.render(size)
                try {
                    require(expected.sameAs(actual), "shared artwork pixel mismatch at size $size")
                } finally {
                    expected.recycle()
                    actual.recycle()
                }
            }
        }
    }

    private fun verifyCorners() {
        val reference = ReferenceCorners()
        val builder = ContinuousCorners.Builder()
        val random = Random(139)
        val bounds = RectF()
        for (i in 0 until 1000) {
            val left = random.nextFloat() * 100
            val top = random.nextFloat() * 100
            val width = 1 + random.nextFloat() * 20
            val height = 0.1f + random.nextFloat() * 200
            val radius = 0.01f + random.nextFloat() * width
            bounds.set(left, top, left + width, top + height)
            val expected = reference.build(bounds, radius).approximate(0.0001f)
            val actual = builder.build(bounds, radius)
            require(expected.contentEquals(actual.approximate(0.0001f)), "corner path mismatch $i")
            require(actual === builder.build(bounds, radius), "cached path identity")
        }
        bounds.set(0f, 0f, 0f, 1f)
        require(builder.build(bounds, 1f).isEmpty, "invalid bounds retain stale path")
        bounds.set(0f, 0f, 3f, 9f)
        require(reference.build(bounds, 0f).approximate(0.0001f)
                .contentEquals(builder.build(bounds, 0f).approximate(0.0001f)), "zero-radius rectangle")
    }

    private fun verifyConcurrentAudio() {
        val failure = AtomicReference<Throwable>()
        val audio = Thread({
            try {
                val pcm = FloatArray(4096)
                val bands = FloatArray(6)
                pcm[0] = 0.1f
                for (i in 0 until 100) {
                    require(NativeDsp.analyze(pcm, bands), "concurrent DSP failure")
                    for (band in bands) require(band.isFinite(), "nonfinite band")
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "HyperWave-test-audio")
        audio.start()
        val pixels = IntArray(96 * 96)
        for (i in 0 until 20) require(NativeDsp.blurArtwork(pixels, 36), "concurrent blur failure")
        audio.join(10000)
        require(!audio.isAlive, "audio workspace blocked")
        if (failure.get() != null) throw AssertionError("audio failure", failure.get())
    }

    private fun benchmarkArtwork() {
        for (size in intArrayOf(128, 1024)) {
            val source = image(size, size)
            val drawable = BitmapDrawable(targetContext.resources, source)
            try {
                measurePair("artwork_$size",
                        Runnable { ReferenceArtwork.prepare(drawable, 16f).recycle() },
                        Runnable { ArtworkBackdrop.prepare(drawable, 16f)?.recycle() }, 1)
                measurePair("artwork_two_sizes_$size", Runnable {
                    ArtworkBackdrop.prepare(drawable, 16f)!!.recycle()
                    ArtworkBackdrop.prepare(drawable, 22f)!!.recycle()
                }, Runnable {
                    val base = ArtworkBackdrop.prepareBase(drawable)!!
                    base.render(16f).recycle()
                    base.render(22f).recycle()
                }, 1)
            } finally {
                source.recycle()
            }
        }
    }

    private fun benchmarkCorners() {
        val reference = Array(6) { ReferenceCorners() }
        val builders = Array(6) { ContinuousCorners.Builder() }
        val bounds = Array(6) { RectF(it * 7f, 0f, it * 7f + 5, 30f) }
        val ticks = intArrayOf(0, 0)
        measurePair("six_animated_paths", Runnable {
            val height = 20 + (++ticks[0] % 128) * 0.1f
            for (i in 0 until 6) {
                bounds[i].bottom = height
                reference[i].build(bounds[i], 2.5f)
            }
        }, Runnable {
            val height = 20 + (++ticks[1] % 128) * 0.1f
            for (i in 0 until 6) {
                bounds[i].bottom = height
                builders[i].build(bounds[i], 2.5f)
            }
        }, 128)
        for (bound in bounds) bound.bottom = 30f
        measurePair("six_unchanged_paths", Runnable {
            for (i in 0 until 6) reference[i].build(bounds[i], 2.5f)
        }, Runnable {
            for (i in 0 until 6) builders[i].build(bounds[i], 2.5f)
        }, 128)
    }

    private fun measurePair(name: String, reference: Runnable, optimized: Runnable, iterations: Int) {
        for (i in 0 until 8) {
            measure(reference, iterations)
            measure(optimized, iterations)
        }
        val before = DoubleArray(11)
        val after = DoubleArray(11)
        for (i in before.indices) {
            if (i % 2 == 0) {
                before[i] = measure(reference, iterations)
                after[i] = measure(optimized, iterations)
            } else {
                after[i] = measure(optimized, iterations)
                before[i] = measure(reference, iterations)
            }
        }
        before.sort()
        after.sort()
        results.putString(name + "_us", "reference=" + before[5] + ", optimized=" + after[5] +
                ", speedup=" + before[5] / after[5])
    }

    private fun measure(action: Runnable, iterations: Int): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        for (i in 0 until iterations) action.run()
        return (SystemClock.elapsedRealtimeNanos() - start) / (1000.0 * iterations)
    }

    private fun image(width: Int, height: Int): Bitmap {
        val colors = IntArray(width * height)
        var state = 139
        for (i in colors.indices) {
            state = state * 1664525 + 1013904223
            colors[i] = state
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
