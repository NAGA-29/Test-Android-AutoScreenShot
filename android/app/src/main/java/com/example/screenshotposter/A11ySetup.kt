package com.example.screenshotposter

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * フェーズ2のセットアップ。
 *
 * 非 root 端末の前提(初回のみ、手動の adb 1コマンド):
 *   adb shell pm grant com.example.screenshotposter android.permission.WRITE_SECURE_SETTINGS
 * root 端末ではアプリが su 経由で自分に付与するので adb は不要。
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

    private fun nextValue(context: Context): String {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return if (TextUtils.isEmpty(current)) componentName(context) else "$current:${componentName(context)}"
    }

    /** WRITE_SECURE_SETTINGS があれば自分で書き込む。無ければ SecurityException を握りつぶして false。 */
    private fun putDirectly(context: Context): Boolean = try {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            nextValue(context),
        )
        // Android 13+ でも WRITE_SECURE_SETTINGS があればこの書き込みは「制限付き設定」の対象外。
        isEnabled(context)
    } catch (e: SecurityException) {
        false
    }

    /**
     * a11y を無人で有効化する。ブロッキング (root 経由の su を待つので IO スレッドから呼ぶこと)。
     * 1. WRITE_SECURE_SETTINGS が付与済みなら直接書く
     * 2. root 端末なら、自分で `pm grant` してから再試行 (adb 不要)
     * 3. それでもだめなら root で `settings put secure ...` を直接実行
     * 全部だめなら false (呼び出し側で手動設定 / adb pm grant を案内する)。
     */
    fun tryEnableSilently(context: Context): Boolean {
        if (isEnabled(context)) return true
        if (putDirectly(context)) return true

        val root = RootScreenSource()
        if (!root.ensureRoot()) return false

        root.runAsRoot(listOf("pm", "grant", context.packageName, "android.permission.WRITE_SECURE_SETTINGS"))
        if (putDirectly(context)) return true

        root.runAsRoot(listOf("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, nextValue(context)))
        root.runAsRoot(listOf("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, "1"))
        return isEnabled(context)
    }
}
