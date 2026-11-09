package com.example.screenshotposter

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** サーバー契約: multipart/form-data, part `file`, filename `shot_<epochMillis>.jpg`, image/jpeg。2xx で成功。 */
class Uploader(private val url: String = Config.POST_URL) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(Config.HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(Config.HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(Config.HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** ブロッキング。IO ディスパッチャから呼ぶこと。成功なら null、失敗ならエラー内容。 */
    fun post(fileName: String, jpeg: ByteArray): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder().url(url).post(body).apply {
            if (Config.AUTH_TOKEN.isNotEmpty()) header("Authorization", "Bearer ${Config.AUTH_TOKEN}")
        }.build()
        return try {
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) null else "HTTP ${res.code}".also { Log.w(TAG, "upload $fileName: $it") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload $fileName failed", e)
            e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    private companion object { const val TAG = "Uploader" }
}
