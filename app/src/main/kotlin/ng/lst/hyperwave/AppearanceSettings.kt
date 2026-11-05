package ng.lst.hyperwave

import android.content.Context
import android.net.Uri

/** Device-protected appearance preferences shared with the injected renderer. */
object AppearanceSettings {
    const val AUTHORITY = "ng.lst.hyperwave.settings"
    val URI: Uri = Uri.parse("content://$AUTHORITY/appearance")
    const val COLUMN_STYLE = "style"
    const val COLUMN_HEIGHT_PERCENT = "height_percent"
    const val COLUMN_ARTWORK_FLIP = "artwork_flip"
    const val DEFAULT_ARTWORK_FLIP = true
    const val MIN_HEIGHT_PERCENT = 50
    const val MAX_HEIGHT_PERCENT = 200
    const val DEFAULT_HEIGHT_PERCENT = 150
    private const val PREFERENCES = "appearance"

    enum class Style(val value: String) {
        IOS("ios"), HYPEROS("hyperos"), MIOS("mios");

        val usesSixBars: Boolean get() = this != HYPEROS

        companion object {
            fun parse(value: String?): Style = entries.firstOrNull { it.value == value } ?: HYPEROS
        }
    }

    fun load(context: Context): Style =
            Style.parse(context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getString(COLUMN_STYLE, Style.HYPEROS.value))

    fun loadHeightPercent(context: Context): Int =
            context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getInt(COLUMN_HEIGHT_PERCENT, DEFAULT_HEIGHT_PERCENT)
                    .coerceIn(MIN_HEIGHT_PERCENT, MAX_HEIGHT_PERCENT)

    fun loadArtworkFlip(context: Context): Boolean =
            context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getBoolean(COLUMN_ARTWORK_FLIP, DEFAULT_ARTWORK_FLIP)

    fun saveArtworkFlip(context: Context, enabled: Boolean) {
        context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(COLUMN_ARTWORK_FLIP, enabled).apply()
        context.contentResolver.notifyChange(URI, null)
    }

    fun saveHeightPercent(context: Context, percent: Int) {
        context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putInt(COLUMN_HEIGHT_PERCENT,
                        percent.coerceIn(MIN_HEIGHT_PERCENT, MAX_HEIGHT_PERCENT)).apply()
        context.contentResolver.notifyChange(URI, null)
    }

    fun save(context: Context, style: Style) {
        context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(COLUMN_STYLE, style.value).apply()
        context.contentResolver.notifyChange(URI, null)
    }
}
