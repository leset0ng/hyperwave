package dev.hyperwave;

import android.content.Context;
import android.net.Uri;

/** Device-protected appearance preferences shared with the injected renderer. */
public final class AppearanceSettings {
    public static final String AUTHORITY = "dev.hyperwave.settings";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/appearance");
    public static final String COLUMN_STYLE = "style";
    private static final String PREFERENCES = "appearance";

    public enum Style {
        IOS("ios"), HYPEROS("hyperos");
        public final String value;
        Style(String value) { this.value = value; }
        public static Style parse(String value) {
            return IOS.value.equals(value) ? IOS : HYPEROS;
        }
    }

    private AppearanceSettings() {}

    public static Style load(Context context) {
        return Style.parse(context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(COLUMN_STYLE, Style.HYPEROS.value));
    }

    public static void save(Context context, Style style) {
        context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(COLUMN_STYLE, style.value).apply();
        context.getContentResolver().notifyChange(URI, null);
    }
}
