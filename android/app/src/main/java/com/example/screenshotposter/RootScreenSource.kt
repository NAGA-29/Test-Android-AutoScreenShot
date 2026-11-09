package com.example.screenshotposter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** フェーズ1: `su -c screencap` 。MediaProjection は使わない。 */
class RootScreenSource(context: Context) : ScreenSource {
    override val name = "root(screencap)"

    // 仕様書は /data/local/tmp を例示しているが、/data/local/tmp は shell 所有の 0771 で
    // 一般アプリから読めない端末が多い。アプリの cacheDir に root で書き、chmod で読めるようにする。
    private val file = File(context.cacheDir, "shot.png")

    override suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        val path = file.absolutePath
        // stdout を直接読むと化けることがあるので一旦ファイルに吐く
        val out = Su.run("screencap -p '$path' && chmod 666 '$path'", 10)
        if (out == null) return@withContext null
        BitmapFactory.decodeFile(path).also {
            if (it == null) Log.w(TAG, "decode failed. su output: $out")
        }
    }

    private companion object { const val TAG = "RootScreenSource" }
}

object Su {
    private const val TAG = "Su"

    /** su -c <cmd> を実行。成功(exit 0)なら出力、失敗・タイムアウトなら null。 */
    fun run(cmd: String, timeoutSec: Long): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        // 先にタイムアウト付きで待つ (su が承認待ちで固まっても永久に readText() で
        // ブロックしないため)。出力は id / screencap のエラー程度で小さく、
        // パイプが詰まって waitFor が戻らなくなる心配はない。
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "timeout: $cmd")
            null
        } else {
            val text = p.inputStream.bufferedReader().readText()
            if (p.exitValue() != 0) {
                Log.w(TAG, "exit ${p.exitValue()}: $cmd -> $text")
                null
            } else text
        }
    } catch (e: Exception) { // su が無い端末は IOException
        Log.w(TAG, "su unavailable: ${e.message}")
        null
    }

    /** root を取れるか(Magisk で「常に許可」済みなら無人で true になる)。ブロッキング。 */
    fun available(): Boolean = run("id", 15)?.contains("uid=0") == true
}
