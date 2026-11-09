package com.example.screenshotposter

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 共通パイプライン (取得方式に依存しない):
 *   capture() → downscale → (差分検知) → JPEG → Outbox → POST → 失敗なら残して次回リトライ
 */
class CapturePipeline(
    private val source: ScreenSource,
    private val outbox: Outbox,
    private val uploader: Uploader,
) {
    private var lastSig: IntArray? = null
    private var lastSentAt = 0L

    suspend fun run() {
        while (coroutineContext.isActive) {
            val started = System.currentTimeMillis()
            try {
                step()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { // 1フレームの失敗でループを止めない
                Log.e(TAG, "step failed", e)
                CaptureState.lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            }
            val elapsed = System.currentTimeMillis() - started
            delay((Config.CAPTURE_INTERVAL_MS - elapsed).coerceAtLeast(200L))
        }
    }

    private suspend fun step() {
        val raw = source.capture()
        if (raw == null) {
            CaptureState.lastError = "capture failed (${source.name})"
        } else {
            CaptureState.captured++
            withContext(Dispatchers.Default) { process(raw) }
        }
        flush() // 取得に失敗しても溜まった分は送る
    }

    private fun process(raw: android.graphics.Bitmap) {
        val bmp = ImageProcessor.downscale(raw, Config.TARGET_WIDTH)
        try {
            val now = System.currentTimeMillis()
            if (Config.SKIP_UNCHANGED) {
                val sig = ImageProcessor.signature(bmp)
                val prev = lastSig
                val unchanged = prev != null &&
                    ImageProcessor.meanAbsDiff(prev, sig) < Config.DIFF_THRESHOLD &&
                    now - lastSentAt < Config.HEARTBEAT_MS
                if (unchanged) {
                    CaptureState.skipped++
                    CaptureState.lastEvent = "unchanged (skipped)"
                    return
                }
                lastSig = sig
            }
            val jpeg = ImageProcessor.toJpeg(bmp, Config.JPEG_QUALITY)
            outbox.enqueue("shot_$now.jpg", jpeg)
            lastSentAt = now
            CaptureState.lastEvent = "queued ${ImageProcessor.kb(jpeg.size)}KB ${bmp.width}x${bmp.height}"
        } finally {
            if (bmp !== raw) bmp.recycle()
            raw.recycle()
        }
    }

    /** 古い順に送る。1件でも失敗したら止めて次のティックで再試行(順序維持)。 */
    private suspend fun flush() = withContext(Dispatchers.IO) {
        for (f: File in outbox.pending()) {
            val err = uploader.post(f.name, f.readBytes())
            if (err != null) {
                CaptureState.lastError = "upload: $err"
                break
            }
            f.delete()
            CaptureState.sent++
            CaptureState.lastError = null
        }
        CaptureState.pending = outbox.count()
    }

    private companion object { const val TAG = "CapturePipeline" }
}
