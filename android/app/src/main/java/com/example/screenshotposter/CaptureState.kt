package com.example.screenshotposter

/** サービス → UI へ状態を渡すだけの簡易ホルダ。 */
object CaptureState {
    @Volatile var running = false
    @Volatile var sourceName = "-"
    @Volatile var lastEvent = "-"
    @Volatile var lastError: String? = null
    @Volatile var frameInfo = "-"            // 黒画像の切り分け用 (RootScreenSource が更新)
    @Volatile var captured = 0
    @Volatile var sent = 0
    @Volatile var skipped = 0
    @Volatile var pending = 0

    fun reset() {
        sourceName = "-"; lastEvent = "-"; lastError = null; frameInfo = "-"
        captured = 0; sent = 0; skipped = 0; pending = 0
    }
}
