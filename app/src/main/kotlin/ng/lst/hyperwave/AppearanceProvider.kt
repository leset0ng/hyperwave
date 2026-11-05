package ng.lst.hyperwave

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/** Read-only settings bridge; other applications cannot change the appearance. */
class AppearanceProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(AppearanceSettings.URI == uri) { "Unknown settings URI" }
        val context = checkNotNull(context)
        val cursor = MatrixCursor(arrayOf(AppearanceSettings.COLUMN_STYLE,
                AppearanceSettings.COLUMN_HEIGHT_PERCENT), 1)
        cursor.addRow(arrayOf<Any>(AppearanceSettings.load(context).value,
                AppearanceSettings.loadHeightPercent(context)))
        cursor.setNotificationUri(context.contentResolver, AppearanceSettings.URI)
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.hyperwave.appearance"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
            throw SecurityException("Read-only provider")

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int =
            throw SecurityException("Read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw SecurityException("Read-only provider")
}
