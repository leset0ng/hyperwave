package dev.hyperwave.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import dev.hyperwave.audio.NativeDsp;

/** Artwork treatment in the recovered order; blur is a separable Gaussian approximation. */
public final class ArtworkBackdrop {
    private static final int SIZE = 96;
    private static final ThreadLocal<int[]> PIXEL_BUFFERS = ThreadLocal.withInitial(() -> new int[64 * 1024]);
    private ArtworkBackdrop() {}

    public static Bitmap prepare(Drawable drawable, float logicalSize) {
        if (!(logicalSize > 0) || !Float.isFinite(logicalSize))
            throw new IllegalArgumentException("Expected a finite positive logical size");
        Bitmap source;
        boolean owned;
        if (drawable instanceof BitmapDrawable bitmapDrawable) {
            source = bitmapDrawable.getBitmap();
            if (source == null || source.isRecycled()) return null;
            owned = source.getConfig() == Bitmap.Config.HARDWARE;
            if (owned) source = source.copy(Bitmap.Config.ARGB_8888, false);
        } else {
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            float scale = Math.min(1, 1024f / Math.max(width, height));
            source = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
            owned = true;
            Rect previousBounds = drawable.copyBounds();
            try {
                drawable.setBounds(0, 0, source.getWidth(), source.getHeight());
                drawable.draw(new Canvas(source));
            } finally {
                drawable.setBounds(previousBounds);
            }
        }
        if (source == null) return null;
        try {
            return prepare(source, logicalSize);
        } finally {
            if (owned) source.recycle();
        }
    }

    private static Bitmap prepare(Bitmap source, float logicalSize) {
        int[] pixels = PIXEL_BUFFERS.get();
        if (pixels.length < source.getWidth()) {
            pixels = new int[source.getWidth()];
            PIXEL_BUFFERS.set(pixels);
        }
        double brightness = 0;
        int rowsPerTile = pixels.length / source.getWidth();
        for (int y = 0; y < source.getHeight(); y += rowsPerTile) {
            int rows = Math.min(rowsPerTile, source.getHeight() - y);
            source.getPixels(pixels, 0, source.getWidth(), 0, y, source.getWidth(), rows);
            brightness = NativeDsp.sumArtworkBrightness(pixels, source.getWidth() * rows, brightness);
            if (brightness < 0) throw new IllegalStateException("Rust rejected the artwork pixels");
        }
        brightness /= (double) source.getWidth() * source.getHeight();
        float lightening = (float) Math.max(0, .6 - brightness);
        float darkening = (float) Math.max(0, brightness - .7);
        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(1.25f + 4 * lightening);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(saturation));
        Bitmap result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        int side = Math.min(source.getWidth(), source.getHeight());
        int left = (source.getWidth() - side) / 2;
        int top = (source.getHeight() - side) / 2;
        canvas.drawBitmap(source, new Rect(left, top, left + side, top + side), new Rect(0, 0, SIZE, SIZE), paint);
        canvas.drawColor(Color.argb(Math.round(255 * darkening), 0, 0, 0));
        canvas.drawColor(Color.argb(Math.round(255 * lightening), 255, 255, 255));
        result.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        int radius = Math.min(SIZE, Math.max(1, Math.round(6f / logicalSize * SIZE)));
        if (!NativeDsp.blurArtwork(pixels, radius))
            throw new IllegalStateException("Rust rejected the artwork blur");
        result.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        return result;
    }
}
