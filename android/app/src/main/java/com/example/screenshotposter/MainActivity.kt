package com.example.screenshotposter

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var textStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textStatus = findViewById(R.id.textStatus)

        findViewById<Button>(R.id.buttonStart).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java))
        }

        findViewById<Button>(R.id.buttonStop).setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
        }

        findViewById<Button>(R.id.buttonEnableA11y).setOnClickListener {
            // root 経由の su を待つのでメインスレッドでは呼ばない
            Thread {
                val ok = A11ySetup.tryEnableSilently(this)
                runOnUiThread {
                    textStatus.text = if (ok) "a11y 有効化 OK" else "a11y 有効化 失敗 (root なし かつ WRITE_SECURE_SETTINGS 未付与?)"
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun renderStatus() {
        textStatus.text = buildString {
            append("running=${CaptureState.running}\n")
            append("source=${CaptureState.sourceName}\n")
            append("captured=${CaptureState.captured} sent=${CaptureState.sent} skipped=${CaptureState.skipped} pending=${CaptureState.pending}\n")
            append("last=${CaptureState.lastEvent}\n")
            append("frame=${CaptureState.frameInfo}\n")
            append("error=${CaptureState.lastError ?: "-"}\n")
            append("a11y_enabled=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) A11ySetup.isEnabled(this@MainActivity) else "N/A (<API30)"}\n")
        }
    }
}
