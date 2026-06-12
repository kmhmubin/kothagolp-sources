package com.kmhmubin.kothagolp.data.remote

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object NetworkClient {
    data class NetworkResponse(
        val document: Document,
        val text: String,
        val isSuccessful: Boolean,
        val code: Int,
        val isCloudflareBlocked: Boolean = false,
        val headers: Map<String, String> = emptyMap()
    )

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): NetworkResponse =
        throw NotImplementedError("Stub — replaced by host classloader at runtime")

    suspend fun post(
        url: String,
        data: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): NetworkResponse =
        throw NotImplementedError("Stub — replaced by host classloader at runtime")

    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): NetworkResponse =
        throw NotImplementedError("Stub — replaced by host classloader at runtime")
}
