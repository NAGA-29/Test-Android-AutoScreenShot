package com.example.screenshotposter

import android.graphics.Bitmap

/**
 * 画面1フレームの取得方式。下流(縮小・JPEG・送信・リトライ)は共通で、ここだけ差し替える。
 *
 * 仕様書の `capture(): ByteArray?` と違い Bitmap を返す。
 * 縮小・差分検知は Bitmap 段階で行うため、各実装に圧縮を重複させないための判断。
 * 失敗時は null。返した Bitmap の recycle は呼び出し側が行う。
 */
interface ScreenSource {
    val name: String
    suspend fun capture(): Bitmap?
}
