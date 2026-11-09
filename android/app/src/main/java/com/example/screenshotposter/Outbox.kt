package com.example.screenshotposter

import android.util.Log
import java.io.File

/**
 * 送信待ちの JPEG をディスクに退避する。ファイル名がそのまま送信時の filename になる。
 * 名前が epoch ミリ秒なので、文字列ソート(桁数が同じ間)＝時系列。
 */
class Outbox(private val dir: File, private val maxFiles: Int = Config.OUTBOX_MAX_FILES) {

    init { dir.mkdirs() }

    fun enqueue(fileName: String, jpeg: ByteArray) {
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeBytes(jpeg)
        if (!tmp.renameTo(File(dir, fileName))) { // 書きかけを送らないよう rename で確定
            tmp.delete()
            Log.w(TAG, "rename failed: $fileName")
            return
        }
        trim()
    }

    /** 古い順。 */
    fun pending(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    fun count(): Int = pending().size

    private fun trim() {
        val files = pending()
        if (files.size > maxFiles) files.take(files.size - maxFiles).forEach { it.delete() }
    }

    private companion object { const val TAG = "Outbox" }
}
