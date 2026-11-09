package com.example.screenshotposter

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * フェーズ2: takeScreenshot() を呼ぶためだけの a11y サービス。イベントは見ない。
 * 実際の定期実行は CaptureService 側のループが [A11yScreenSource] 経由でここを叩く。
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile var instance: ScreenshotAccessibilityService? = null
            private set
    }
}
