package dev.hyperwave.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import dev.hyperwave.audio.NativeDsp;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free device regression and paired, warmed performance measurements. */
public final class PerformanceInstrumentation extends Instrumentation {
    private final Bundle results = new Bundle();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        try {
            NativeDsp.load(getTargetContext().getApplicationInfo().nativeLibraryDir);
            verifyNativeBounds();
            verifyArtwork();
            verifyCorners();
            verifyConcurrentAudio();
            benchmarkArtwork();
            benchmarkCorners();
            results.putString("checks", "PASS: artwork pixels, hardware copies, JNI bounds, corner paths, concurrent audio");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("failure", Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, results);
        }
    }

    private void verifyNativeBounds() {
        int[] pixels = new int[96 * 96 + 1];
        pixels[pixels.length - 1] = 0x12345678;
        require(NativeDsp.sumArtworkBrightness(pixels, 0, 0) == -1, "zero pixel count");
        require(NativeDsp.sumArtworkBrightness(pixels, pixels.length + 1, 0) == -1, "pixel count overflow");
        require(NativeDsp.sumArtworkBrightness(pixels, 1, Double.NaN) == -1, "nonfinite sum");
        require(!NativeDsp.blurArtwork(new int[1], 26), "short blur input");
        require(!NativeDsp.blurArtwork(pixels, 0) && !NativeDsp.blurArtwork(pixels, 97), "invalid blur radius");
        require(NativeDsp.blurArtwork(pixels, 26), "valid blur input");
        require(pixels[pixels.length - 1] == 0x12345678, "blur wrote beyond image");
        require(!NativeDsp.analyze(new float[2], new float[6]), "short PCM input");
    }

    private void verifyArtwork() {
        for (int[] dimensions : new int[][]{{1, 1}, {127, 191}, {1024, 768}}) {
            Bitmap source = image(dimensions[0], dimensions[1]);
            try {
                compareArtwork(source);
                Bitmap hardware = source.copy(Bitmap.Config.HARDWARE, false);
                require(hardware != null, "hardware bitmap copy");
                try { compareArtwork(hardware); } finally { hardware.recycle(); }
                require(!source.isRecycled(), "shared source was recycled");
            } finally { source.recycle(); }
        }
        for (int color : new int[]{0xff000000, 0xffffffff, 0x7f444444, 0xffcccccc}) {
            Bitmap source = Bitmap.createBitmap(99, 101, Bitmap.Config.ARGB_8888);
            source.eraseColor(color);
            try { compareArtwork(source); } finally { source.recycle(); }
        }
    }

    private void compareArtwork(Bitmap source) {
        BitmapDrawable drawable = new BitmapDrawable(getTargetContext().getResources(), source);
        for (float size : new float[]{16, 22}) {
            Bitmap expected = ReferenceArtwork.prepare(drawable, size);
            Bitmap actual = ArtworkBackdrop.prepare(drawable, size);
            try { require(actual != null && expected.sameAs(actual), "artwork pixel mismatch at size " + size); }
            finally {
                expected.recycle();
                if (actual != null) actual.recycle();
            }
            require(!source.isRecycled(), "caller-owned bitmap was recycled");
        }
    }

    private void verifyCorners() throws Exception {
        ReferenceCorners reference = new ReferenceCorners();
        ContinuousCorners.Builder builder = new ContinuousCorners.Builder();
        Random random = new Random(139);
        RectF bounds = new RectF();
        for (int i = 0; i < 1000; i++) {
            float left = random.nextFloat() * 100, top = random.nextFloat() * 100;
            float width = 1 + random.nextFloat() * 20, height = .1f + random.nextFloat() * 200;
            float radius = .01f + random.nextFloat() * width;
            bounds.set(left, top, left + width, top + height);
            float[] expected = reference.build(bounds, radius).approximate(.0001f);
            Path actual = builder.build(bounds, radius);
            require(Arrays.equals(expected, actual.approximate(.0001f)), "corner path mismatch " + i);
            require(actual == builder.build(bounds, radius), "cached path identity");
        }
        bounds.set(0, 0, 0, 1);
        require(builder.build(bounds, 1).isEmpty(), "invalid bounds retain stale path");
        bounds.set(0, 0, 3, 9);
        require(Arrays.equals(reference.build(bounds, 0).approximate(.0001f),
                builder.build(bounds, 0).approximate(.0001f)), "zero-radius rectangle");
    }

    private void verifyConcurrentAudio() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread audio = new Thread(() -> {
            try {
                float[] pcm = new float[4096], bands = new float[6];
                pcm[0] = .1f;
                for (int i = 0; i < 100; i++) {
                    require(NativeDsp.analyze(pcm, bands), "concurrent DSP failure");
                    for (float band : bands) require(Float.isFinite(band), "nonfinite band");
                }
            } catch (Throwable error) { failure.set(error); }
        }, "Hyperwave-test-audio");
        audio.start();
        int[] pixels = new int[96 * 96];
        for (int i = 0; i < 20; i++) require(NativeDsp.blurArtwork(pixels, 36), "concurrent blur failure");
        audio.join(10000);
        require(!audio.isAlive(), "audio workspace blocked");
        if (failure.get() != null) throw new AssertionError("audio failure", failure.get());
    }

    private void benchmarkArtwork() {
        for (int size : new int[]{128, 1024}) {
            Bitmap source = image(size, size);
            BitmapDrawable drawable = new BitmapDrawable(getTargetContext().getResources(), source);
            try {
                measurePair("artwork_" + size, () -> ReferenceArtwork.prepare(drawable, 16).recycle(),
                        () -> ArtworkBackdrop.prepare(drawable, 16).recycle(), 1);
            } finally { source.recycle(); }
        }
    }

    private void benchmarkCorners() throws Exception {
        ReferenceCorners[] reference = new ReferenceCorners[6];
        ContinuousCorners.Builder[] builders = new ContinuousCorners.Builder[6];
        RectF[] bounds = new RectF[6];
        for (int i = 0; i < 6; i++) {
            reference[i] = new ReferenceCorners();
            builders[i] = new ContinuousCorners.Builder();
            bounds[i] = new RectF(i * 7, 0, i * 7 + 5, 30);
        }
        int[] ticks = new int[2];
        measurePair("six_animated_paths", () -> {
            float height = 20 + (++ticks[0] % 128) * .1f;
            for (int i = 0; i < 6; i++) {
                bounds[i].bottom = height;
                reference[i].build(bounds[i], 2.5f);
            }
        }, () -> {
            float height = 20 + (++ticks[1] % 128) * .1f;
            for (int i = 0; i < 6; i++) {
                bounds[i].bottom = height;
                builders[i].build(bounds[i], 2.5f);
            }
        }, 128);
        for (RectF bound : bounds) bound.bottom = 30;
        measurePair("six_unchanged_paths", () -> {
            for (int i = 0; i < 6; i++) reference[i].build(bounds[i], 2.5f);
        }, () -> {
            for (int i = 0; i < 6; i++) builders[i].build(bounds[i], 2.5f);
        }, 128);
    }

    private void measurePair(String name, Runnable reference, Runnable optimized, int iterations) {
        for (int i = 0; i < 8; i++) {
            measure(reference, iterations);
            measure(optimized, iterations);
        }
        double[] before = new double[11], after = new double[11];
        for (int i = 0; i < before.length; i++) {
            if (i % 2 == 0) {
                before[i] = measure(reference, iterations);
                after[i] = measure(optimized, iterations);
            } else {
                after[i] = measure(optimized, iterations);
                before[i] = measure(reference, iterations);
            }
        }
        Arrays.sort(before);
        Arrays.sort(after);
        results.putString(name + "_us", "reference=" + before[5] + ", optimized=" + after[5]
                + ", speedup=" + before[5] / after[5]);
    }

    private static double measure(Runnable action, int iterations) {
        long start = SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < iterations; i++) action.run();
        return (SystemClock.elapsedRealtimeNanos() - start) / (1000.0 * iterations);
    }

    private static Bitmap image(int width, int height) {
        int[] colors = new int[width * height];
        int state = 139;
        for (int i = 0; i < colors.length; i++) {
            state = state * 1664525 + 1013904223;
            colors[i] = state;
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
