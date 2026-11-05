package dev.hyperwave

import android.content.Context
import android.net.Uri

/** Device-protected appearance preferences shared with the injected renderer. */
object AppearanceSettings {
    const val AUTHORITY = "dev.hyperwave.settings"
    val URI: Uri = Uri.parse("content://$AUTHORITY/appearance")
    const val COLUMN_STYLE = "style"
    const val COLUMN_HEIGHT_PERCENT = "height_percent"
    const val MIN_HEIGHT_PERCENT = 50
    const val MAX_HEIGHT_PERCENT = 200
    const val DEFAULT_HEIGHT_PERCENT = 150
    private const val PREFERENCES = "appearance"

    enum class Style(val value: String) {
        IOS("ios"), HYPEROS("hyperos");

        companion object {
            fun parse(value: String?): Style = if (IOS.value == value) IOS else HYPEROS
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
