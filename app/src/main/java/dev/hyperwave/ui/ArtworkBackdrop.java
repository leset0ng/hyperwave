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

/** Artwork treatment in the recovered order; blur is a separable Gaussian approximation. */
public final class ArtworkBackdrop {
    private static final int SIZE = 96;
    private ArtworkBackdrop() {}

    public static Bitmap prepare(Drawable drawable, float logicalSize) {
        Bitmap source;
        if (drawable instanceof BitmapDrawable bitmapDrawable) {
            source = bitmapDrawable.getBitmap();
            if (source.getConfig() == Bitmap.Config.HARDWARE) source = source.copy(Bitmap.Config.ARGB_8888, false);
        } else {
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            float scale = Math.min(1, 1024f / Math.max(width, height));
            source = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, source.getWidth(), source.getHeight());
            drawable.draw(new Canvas(source));
        }
        if (source == null || source.isRecycled()) return null;
        double brightness = 0;
        int[] row = new int[source.getWidth()];
        for (int y = 0; y < source.getHeight(); y++) {
            source.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int color : row) {
                double r = Color.red(color) / 255.0;
                double g = Color.green(color) / 255.0;
                double b = Color.blue(color) / 255.0;
                brightness += Math.sqrt(.299 * r * r + .587 * g * g + .114 * b * b);
            }
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
        int[] pixels = new int[SIZE * SIZE];
        int[] scratch = new int[pixels.length];
        result.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        // Three equal box filters approximate a Gaussian at the recovered logical blur radius.
        int radius = Math.max(1, Math.round(6f / logicalSize * SIZE));
        for (int pass = 0; pass < 3; pass++) {
            box(pixels, scratch, radius, false);
            box(scratch, pixels, radius, true);
        }
        result.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        return result;
    }

    private static void box(int[] source, int[] target, int radius, boolean vertical) {
        int count = 2 * radius + 1;
        for (int line = 0; line < SIZE; line++) {
            int a = 0, r = 0, g = 0, b = 0;
            for (int position = -radius; position <= radius; position++) {
                int index = Math.max(0, Math.min(SIZE - 1, position));
                int color = source[vertical ? index * SIZE + line : line * SIZE + index];
                a += Color.alpha(color); r += Color.red(color); g += Color.green(color); b += Color.blue(color);
            }
            for (int position = 0; position < SIZE; position++) {
                target[vertical ? position * SIZE + line : line * SIZE + position] =
                        Color.argb(a / count, r / count, g / count, b / count);
                int remove = Math.max(0, position - radius);
                int add = Math.min(SIZE - 1, position + radius + 1);
                int oldColor = source[vertical ? remove * SIZE + line : line * SIZE + remove];
                int newColor = source[vertical ? add * SIZE + line : line * SIZE + add];
                a += Color.alpha(newColor) - Color.alpha(oldColor);
                r += Color.red(newColor) - Color.red(oldColor);
                g += Color.green(newColor) - Color.green(oldColor);
                b += Color.blue(newColor) - Color.blue(oldColor);
            }
        }
    }
}
