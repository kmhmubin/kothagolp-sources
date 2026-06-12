package com.kmhmubin.kothagolp.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Real JVM implementation of NetworkClient for test runtime.
 * This shadows the compileOnly stub from :source-api at test time.
 * Must mirror the exact class/function signatures of the host's real NetworkClient.
 */
object NetworkClient {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class NetworkResponse(
        val document: Document,
        val text: String,
        val isSuccessful: Boolean,
        val code: Int,
        val isCloudflareBlocked: Boolean = false,
        val headers: Map<String, String> = emptyMap()
    )

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): NetworkResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .also { builder ->
                builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                builder.header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                builder.header("Accept-Language", "en-US,en;q=0.9")
                headers.forEach { (k, v) -> builder.header(k, v) }
            }
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        buildResponse(response.code, body, url, response.headers)
    }

    suspend fun post(
        url: String,
        data: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): NetworkResponse = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .also { b -> data.forEach { (k, v) -> b.add(k, v) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .also { builder ->
                builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                headers.forEach { (k, v) -> builder.header(k, v) }
            }
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        buildResponse(response.code, body, url, response.headers)
    }

    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): NetworkResponse = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody(mediaType))
            .also { builder ->
                builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                headers.forEach { (k, v) -> builder.header(k, v) }
            }
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        buildResponse(response.code, body, url, response.headers)
    }

    private fun buildResponse(
        code: Int,
        body: String,
        url: String,
        headers: okhttp3.Headers
    ): NetworkResponse {
        val cfMarkers = listOf("cf-browser-verification", "cf_chl_opt", "Just a moment", "Checking your browser")
        val isCloudflare = code in listOf(403, 503) && cfMarkers.any { body.contains(it, ignoreCase = true) }
        return NetworkResponse(
            document = Jsoup.parse(body, url),
            text = body,
            isSuccessful = code in 200..299,
            code = code,
            isCloudflareBlocked = isCloudflare,
            headers = (0 until headers.size).associate { headers.name(it) to headers.value(it) }
        )
    }
}
