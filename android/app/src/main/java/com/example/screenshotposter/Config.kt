package com.example.screenshotposter

/** 設定値はここに集約する。 */
object Config {
    const val POST_URL = "https://example.com/upload" // 要書き換え (wrangler dev なら http://<PCのIP>:8787/upload)
    const val AUTH_TOKEN = ""                         // サーバー側 AUTH_TOKEN と同じ値。空なら Authorization を付けない
    const val CAPTURE_INTERVAL_MS = 5_000L            // a11y takeScreenshot() の下限(約1秒弱)を割らないこと
    const val TARGET_WIDTH = 720
    const val JPEG_QUALITY = 60

    // 差分検知: 前回送信フレームとほぼ同一なら送らない
    const val SKIP_UNCHANGED = true
    const val DIFF_THRESHOLD = 1.5                    // 32x32 輝度の平均絶対差 (0..255)
    const val HEARTBEAT_MS = 60_000L                  // 変化がなくてもこの間隔では必ず送る

    // outbox (送信失敗分のディスク退避)
    const val OUTBOX_MAX_FILES = 500                  // 超えたら古いものから捨てる
    const val HTTP_TIMEOUT_SEC = 20L
}
