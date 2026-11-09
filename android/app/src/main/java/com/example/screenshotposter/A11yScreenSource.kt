package com.example.screenshotposter

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * フェーズ2: AccessibilityService.takeScreenshot() (API 30+)。
 * ダイアログ・ステータスバー表示なし。FLAG_SECURE 画面は黒塗りになる(仕様)。
 */
@RequiresApi(Build.VERSION_CODES.R)
class A11yScreenSource : ScreenSource {
    override val name = "a11y(takeScreenshot)"

    private val executor = Executors.newSingleThreadExecutor()

    override suspend fun capture(): Bitmap? {
        val svc = ScreenshotAccessibilityService.instance ?: run {
            CaptureState.lastError = "a11y service not connected"
            return null
        }
        return suspendCancellableCoroutine { cont ->
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buf = result.hardwareBuffer
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(buf, result.colorSpace)
                                // HARDWARE Bitmap は getPixels や recycle 周りで扱いにくいので ARGB_8888 にコピー
                                ?.let { hw -> hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() } }
                        } catch (e: Exception) {
                            Log.w(TAG, "convert failed", e)
                            null
                        } finally {
                            buf.close()
                        }
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        // 1=INTERNAL_ERROR 2=NO_ACCESSIBILITY_ACCESS 3=INTERVAL_TIME_SHORT
                        // 4=INVALID_DISPLAY 5=INVALID_WINDOW 6=SECURE_WINDOW (FLAG_SECURE, 仕様通り)
                        CaptureState.lastError = "takeScreenshot failed: code=$errorCode"
                        Log.w(TAG, "takeScreenshot onFailure code=$errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
    }

    private companion object { const val TAG = "A11yScreenSource" }
}
