package com.example.screenshotposter

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * 確認用に最新フレームを Pictures/ScreenshotPoster/latest.jpg に上書き保存する。
 * MediaStore 経由なので権限不要で、ギャラリー / ファイルマネージャー / adb pull で見える。
 * API 29 未満は対象外 (何もしない)。送信用の Outbox とは独立。
 */
object LocalCopy {
    private const val TAG = "LocalCopy"
    private const val DIR = "Download/"
    private const val NAME = "sspost_latest.jpg"

    fun saveLatest(context: Context, jpeg: ByteArray) {
        if (!Config.SAVE_LOCAL_COPY || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            val existing = resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(DIR, NAME),
                null,
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

            val uri = existing ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, DIR)
                },
            ) ?: return

            resolver.openOutputStream(uri, "wt")?.use { it.write(jpeg) }
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }
}
