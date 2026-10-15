package com.zcode.remote.core

import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Serves one verified public script with the bounded-history policy applied. */
object RemoteHistoryResource {
    private const val MAX_SCRIPT_BYTES = 8 * 1024 * 1024
    private val lock = Any()
    @Volatile private var cachedScript: ByteArray? = null
    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** Runs on WebView's request thread; other URLs keep the normal network path. */
    fun intercept(url: String, method: String, userAgent: String?): WebResourceResponse? {
        if (method != "GET" || !RemoteHistoryPatch.matchesUrl(url)) return null
        return try {
            val bytes = cachedScript ?: synchronized(lock) {
                cachedScript ?: fetchScript(url, userAgent)
            }
            WebResourceResponse(
                "application/javascript", "UTF-8", 200, "OK",
                mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream(bytes),
            )
        } catch (e: Exception) {
            // Let WebView fetch the original script normally if this optional path fails.
            Diagnostics.log("warn", "历史窗口补丁未应用，沿用网页原行为：${e.javaClass.simpleName}")
            null
        }
    }

    private fun fetchScript(url: String, userAgent: String?): ByteArray {
        val request = Request.Builder().url(url).apply {
            if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
        }.build()
        // This URL is a public immutable asset. Never forward the page's credential URL or cookies.
        return client.newCall(request).execute().use { response ->
            if (response.code != 200) throw IOException("script HTTP status")
            val body = response.body ?: throw IOException("missing script body")
            if (body.contentLength() > MAX_SCRIPT_BYTES) throw IOException("script too large")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_SCRIPT_BYTES) throw IOException("script too large")
                    output.write(buffer, 0, count)
                }
            }
            val original = output.toByteArray()
            val patched = RemoteHistoryPatch.patch(original.toString(Charsets.UTF_8))
            if (patched == null) {
                Diagnostics.log("warn", "历史窗口补丁指纹不匹配，沿用网页原行为")
                original
            } else {
                patched.toByteArray(Charsets.UTF_8).also {
                    cachedScript = it
                    Diagnostics.info("历史窗口：首屏保留最近 60 行，更早历史按需点击加载（网页 3.15.3）")
                }
            }
        }
    }
}
