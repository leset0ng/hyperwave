package dev.hyperwave

import android.content.Context
import android.net.Uri

/** Device-protected appearance preferences shared with the injected renderer. */
object AppearanceSettings {
    const val AUTHORITY = "dev.hyperwave.settings"
    val URI: Uri = Uri.parse("content://$AUTHORITY/appearance")
    const val COLUMN_STYLE = "style"
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

    fun save(context: Context, style: Style) {
        context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(COLUMN_STYLE, style.value).apply()
        context.contentResolver.notifyChange(URI, null)
    }
}
