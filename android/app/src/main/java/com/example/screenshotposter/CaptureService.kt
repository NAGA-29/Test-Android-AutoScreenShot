package com.example.screenshotposter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * MediaProjection は使わない、プロセスを生かし続けるためだけのプレーンな前景サービス。
 * この中でタイマーループ(CapturePipeline)を回す。
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (job?.isActive != true) start()
        return START_STICKY
    }

    private fun start() {
        // ensureRoot() は su プロセス起動を待つブロッキング呼び出しなので、
        // メインスレッド(onStartCommand)で直接呼ぶと ANR になりうる。IO に逃がす。
        job = scope.launch(Dispatchers.IO) {
            val rootSource = RootScreenSource()
            val source: ScreenSource? = if (rootSource.ensureRoot()) {
                rootSource
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && A11ySetup.isEnabled(applicationContext)) {
                A11yScreenSource()
            } else {
                CaptureState.lastError = "no capture method available (root なし かつ a11y 未設定、または OS < 11)"
                null
            }

            if (source == null) {
                CaptureState.running = false
                stopSelf()
                return@launch
            }

            CaptureState.reset()
            CaptureState.sourceName = source.name
            CaptureState.running = true

            val outbox = Outbox(applicationContext.getDir("outbox", MODE_PRIVATE))
            val pipeline = CapturePipeline(source, outbox, Uploader()) { LocalCopy.saveLatest(applicationContext, it) }
            pipeline.run()
        }
    }

    override fun onDestroy() {
        CaptureState.running = false
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screenshot Poster", NotificationManager.IMPORTANCE_MIN),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screenshot Poster 実行中")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "capture_service"
        private const val NOTIFICATION_ID = 1
    }
}
