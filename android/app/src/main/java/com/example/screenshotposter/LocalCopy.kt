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
    private const val DIR = "Pictures/ScreenshotPoster/"
    private const val NAME = "latest.jpg"

    fun saveLatest(context: Context, jpeg: ByteArray) {
        if (!Config.SAVE_LOCAL_COPY || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val existing = resolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.DISPLAY_NAME}=?",
                arrayOf(DIR, NAME),
                null,
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

            val uri = existing ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, NAME)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, DIR)
                },
            ) ?: return

            resolver.openOutputStream(uri, "wt")?.use { it.write(jpeg) }
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }
}
