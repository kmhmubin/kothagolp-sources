package com.kmhmubin.kothagolp.provider

import com.kmhmubin.kothagolp.data.remote.NetworkClient
import com.kmhmubin.kothagolp.domain.model.FilterGroup
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

abstract class MainProvider {
    abstract val name: String
    abstract val mainUrl: String
    open val hasMainPage: Boolean = true
    open val hasReviews: Boolean = false
    open val tags: List<FilterOption> = emptyList()
    open val orderBys: List<FilterOption> = emptyList()
    open val extraFilterGroups: List<FilterGroup> = emptyList()
    open val rateLimitTime: Long = 0L
    open val iconRes: Int? = null
    open val iconUrl: String? = null

    abstract suspend fun loadMainPage(
        page: Int,
        orderBy: String? = null,
        tag: String? = null,
        extraFilters: Map<String, String> = emptyMap()
    ): MainPageResult

    abstract suspend fun search(query: String): List<Novel>
    abstract suspend fun load(url: String): NovelDetails?
    abstract suspend fun loadChapterContent(url: String): String?

    open suspend fun loadReviews(
        url: String,
        page: Int,
        showSpoilers: Boolean = false
    ): List<Any> = emptyList()

    protected suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): NetworkClient.NetworkResponse = NetworkClient.get(url, headers)

    protected suspend fun post(
        url: String,
        data: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): NetworkClient.NetworkResponse = NetworkClient.post(url, data, headers)

    protected suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): NetworkClient.NetworkResponse = NetworkClient.postJson(url, json, headers)

    protected fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    protected fun Document.selectFirstOrNull(cssQuery: String): Element? =
        this.select(cssQuery).firstOrNull()

    protected fun Element.selectFirstOrNull(cssQuery: String): Element? =
        this.select(cssQuery).firstOrNull()

    protected fun Element.textOrNull(): String? {
        val text = this.text().trim()
        return if (text.isBlank()) null else text
    }

    protected fun Element.attrOrNull(attributeKey: String): String? {
        val value = this.attr(attributeKey).trim()
        return if (value.isBlank()) null else value
    }
}
