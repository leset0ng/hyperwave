package dev.hyperwave;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Read-only settings bridge; other applications cannot change the appearance. */
public final class AppearanceProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (!AppearanceSettings.URI.equals(uri)) throw new IllegalArgumentException("Unknown settings URI");
        MatrixCursor cursor = new MatrixCursor(new String[]{AppearanceSettings.COLUMN_STYLE}, 1);
        cursor.addRow(new Object[]{AppearanceSettings.load(getContext()).value});
        cursor.setNotificationUri(getContext().getContentResolver(), AppearanceSettings.URI);
        return cursor;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.hyperwave.appearance"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("Read-only provider"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new SecurityException("Read-only provider");
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new SecurityException("Read-only provider");
    }
}
