package com.example.screenshotposter

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * フェーズ2のセットアップ。
 *
 * 前提(初回のみ、手動の adb 1コマンド):
 *   adb shell pm grant com.example.screenshotposter android.permission.WRITE_SECURE_SETTINGS
 *
 * 付与後は、このアプリが Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES に
 * 自サービスを書き込んで有効化する(再起動後も残る → 以後無人)。
 */
object A11ySetup {

    private fun componentName(context: Context) =
        "${context.packageName}/${ScreenshotAccessibilityService::class.java.name}"

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(componentName(context), ignoreCase = true) }
    }

    /**
     * WRITE_SECURE_SETTINGS が付与済みなら true を返して有効化。
     * 付与されていなければ SecurityException を握りつぶして false (呼び出し側で手動設定を案内する)。
     */
    fun tryEnableSilently(context: Context): Boolean {
        if (isEnabled(context)) return true
        return try {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val next = if (TextUtils.isEmpty(current)) {
                componentName(context)
            } else {
                "$current:${componentName(context)}"
            }
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                next,
            )
            // Android 13+ でも WRITE_SECURE_SETTINGS があればこの書き込みは「制限付き設定」の対象外。
            isEnabled(context)
        } catch (e: SecurityException) {
            false
        }
    }
}
