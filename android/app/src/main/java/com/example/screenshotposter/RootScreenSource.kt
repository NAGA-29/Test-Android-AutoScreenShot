package com.example.screenshotposter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * フェーズ1: root 経由の `screencap`。MediaProjection は使わない。
 *
 * - root 検出は「バイナリの存在」ではなく、実際に su 経由で `id` を実行して `uid=0` を確認する。
 * - su の書式は端末ごとに違う (AOSP/toybox は `su 0 <cmd>`、Magisk/SuperSU は `su -c <cmd>`)。
 *   複数試して最初に通った書式を [suStyle] にキャッシュする。
 * - 撮影は端末内ファイルに吐き、`cat` の stdout をバイト列で読む。stderr は混ぜない。
 */
class RootScreenSource : ScreenSource {
    override val name = "root(screencap)"

    /** su の呼び出し書式。優先順に試す。 */
    private enum class SuStyle {
        /** `su 0 <cmd...>` (AOSP/toybox) */
        POSITIONAL_UID,

        /** `su root <cmd...>` */
        POSITIONAL_NAME,

        /** `su -c "<cmd>"` (Magisk/SuperSU) */
        DASH_C;

        fun build(cmd: List<String>): List<String> = when (this) {
            POSITIONAL_UID -> listOf("su", "0") + cmd
            POSITIONAL_NAME -> listOf("su", "root") + cmd
            DASH_C -> listOf("su", "-c", cmd.joinToString(" "))
        }
    }

    @Volatile
    private var suStyle: SuStyle? = null

    /**
     * root を取れるか。ブロッキング (su プロセス起動を待つので IO スレッドから呼ぶこと)。
     * 最初に `uid=0` を返した書式をキャッシュする。
     */
    fun ensureRoot(): Boolean {
        if (suStyle != null) return true
        for (style in SuStyle.values()) {
            val r = exec(style.build(listOf("id")), 15) ?: continue
            val text = String(r.stdout)
            if (r.exit == 0 && text.contains("uid=0")) {
                Log.i(TAG, "root ok with style=$style: ${text.trim()}")
                suStyle = style
                return true
            }
            Log.w(TAG, "style=$style rejected: exit=${r.exit} out=${text.trim()} err=${r.stderr.trim()}")
        }
        return false
    }

    /**
     * 任意のコマンドを root で実行する (a11y の自動セットアップ用)。ブロッキング。
     * 成功(exit 0)なら stdout、失敗・root なしなら null。
     */
    fun runAsRoot(cmd: List<String>, timeoutSec: Long = 10): String? {
        if (!ensureRoot()) return null
        val style = suStyle ?: return null
        val r = exec(style.build(cmd), timeoutSec) ?: return null
        if (r.exit != 0) {
            Log.w(TAG, "runAsRoot failed: exit=${r.exit} cmd=$cmd err=${r.stderr.trim()}")
            return null
        }
        return String(r.stdout)
    }

    override suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        if (!ensureRoot()) return@withContext null
        val style = suStyle ?: return@withContext null

        // stdout 直読みは端末によって化けるので、一旦ファイルに吐いてから cat で読む
        val shot = exec(style.build(listOf("screencap", "-p", SHOT_PATH)), 10)
        if (shot == null || shot.exit != 0) {
            Log.w(TAG, "screencap failed: exit=${shot?.exit} err=${shot?.stderr?.trim()}")
            return@withContext null
        }

        val cat = exec(style.build(listOf("cat", SHOT_PATH)), 10)
        if (cat == null || cat.exit != 0 || cat.stdout.isEmpty()) {
            Log.w(TAG, "cat failed: exit=${cat?.exit} bytes=${cat?.stdout?.size} err=${cat?.stderr?.trim()}")
            return@withContext null
        }

        // inPremultiplied=false: alpha=0 の画素の RGB を事前乗算で 0(黒)に潰さないため。
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val decoded = BitmapFactory.decodeByteArray(cat.stdout, 0, cat.stdout.size, opts)
        if (decoded == null) {
            Log.w(TAG, "decode failed (${cat.stdout.size} bytes)")
            return@withContext null
        }
        toOpaque(decoded, cat.stdout.size)
    }

    /**
     * screencap の PNG は端末によって alpha が 0 のことがあり、そのまま JPEG にすると
     * 透明=黒で真っ黒になる。alpha を 0xFF に固定した不透明 Bitmap に作り直す。
     */
    private fun toOpaque(src: Bitmap, pngBytes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()

        var transparent = 0
        var sumLuma = 0L
        for (i in px.indices) {
            val c = px[i]
            if ((c ushr 24) == 0) transparent++
            sumLuma += (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            px[i] = c or (0xFF shl 24)
        }
        // 黒画像の切り分け用 (alpha 全 0 なのか、RGB 自体が黒なのか)
        val info = "${w}x$h png=${pngBytes}B transparent=${transparent * 100L / px.size}% meanLuma=${sumLuma / px.size}"
        CaptureState.frameInfo = info
        Log.d(TAG, "frame $info")
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private class ExecResult(val exit: Int, val stdout: ByteArray, val stderr: String)

    /**
     * コマンドを実行して stdout をバイト列で返す。stderr は別ストリームで読む
     * (redirectErrorStream は使わない: PNG に stderr のテキストが混ざると画像が壊れる)。
     * 起動失敗・タイムアウトは null。
     */
    private fun exec(args: List<String>, timeoutSec: Long): ExecResult? = try {
        val p = ProcessBuilder(args).start()
        p.outputStream.close()

        // 両ストリームを並行して読み切る (どちらかのパイプが詰まって固まるのを避ける)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val outReader = thread(isDaemon = true) { runCatching { p.inputStream.copyTo(out) } }
        val errReader = thread(isDaemon = true) { runCatching { p.errorStream.copyTo(err) } }

        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "timeout: $args")
            null
        } else {
            outReader.join(2_000)
            errReader.join(2_000)
            ExecResult(p.exitValue(), out.toByteArray(), String(err.toByteArray()))
        }
    } catch (e: Exception) { // su が無い端末は IOException
        Log.w(TAG, "exec failed: $args: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "RootScreenSource"
        const val SHOT_PATH = "/data/local/tmp/sspost_shot.png"
    }
}
