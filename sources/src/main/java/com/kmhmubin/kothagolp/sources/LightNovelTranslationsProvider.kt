package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LightNovelTranslationsProvider : MainProvider() {

    override val name = "Light Novel Translations"
    override val mainUrl = "https://lightnovelstranslations.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=lightnovelstranslations.com&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Most Liked", "most-liked"),
        FilterOption("Most Viewed", "most-viewed"),
        FilterOption("Newest", "newest"),
        FilterOption("Latest Update", "latest-update")
    )

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val sort = orderBy.takeUnless { it.isNullOrEmpty() } ?: "most-liked"
        val url = "$mainUrl/read/page/$page?sortby=$sort"
        val document = get(url).document
        val novels = parseNovelList(document)
        return MainPageResult(url = url, novels = novels)
    }

    private fun parseNovelList(document: Document): List<Novel> {
        return document.select("div.read_list-story-item").mapNotNull { element ->
            parseNovelElement(element)
        }
    }

    private fun parseNovelElement(element: Element): Novel? {
        val titleLink = element.selectFirstOrNull("div.item-title a") ?: return null
        val name = titleLink.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = titleLink.attrOrNull("href") ?: return null
        val novelUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return null
        val coverImg = element.selectFirstOrNull("div.item-cover img")
        val posterUrl = coverImg?.attrOrNull("src") ?: coverImg?.attrOrNull("data-src")
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/read"
        val response = post(url, mapOf("field-search" to encodedQuery))
        return parseNovelList(response.document)
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val name = document.selectFirstOrNull("h1.chapter-title-h1")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("h1")?.textOrNull()?.trim()
            ?: return null
        val synopsis = document.selectFirstOrNull("div.chapter_body, div.description-area")
            ?.textOrNull()?.trim()
        val posterUrl = document.selectFirstOrNull("div.item-cover img, div.novel-cover img, div.book-cover img")
            ?.let { it.attrOrNull("src") ?: it.attrOrNull("data-src") }
        val author = document.selectFirstOrNull("div.author a, span.author a")
            ?.textOrNull()?.trim()
        val tags = document.select("div.genres a, div.tags a")
            .mapNotNull { it.textOrNull()?.trim() }.filter { it.isNotBlank() }
        val chapters = parseChapters(document)
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }
        )
    }

    private fun parseChapters(document: Document): List<Chapter> {
        val chapterElements = document.select("ul.chapters-list li.chapter-item.unlock > a")
            .takeIf { it.isNotEmpty() }
            ?: document.select("ul.chapters-list li > a")
        if (chapterElements.isEmpty()) return emptyList()
        return chapterElements.mapNotNull { link ->
            val href = link.attrOrNull("href") ?: return@mapNotNull null
            val chapterUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return@mapNotNull null
            val chapterName = link.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
                ?: link.attrOrNull("title")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val dateElement = link.parent()?.selectFirstOrNull("span.chapter-date")
            val date = dateElement?.textOrNull()?.trim()
            Chapter(name = chapterName, url = chapterUrl, dateOfRelease = date)
        }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val contentElement = document.selectFirstOrNull("div.text_story") ?: return null
        contentElement.select(
            "div.ads_content, div[class*=ads], div[id*=ads], script, style, .adsbygoogle, " +
            "[class*='advertisement'], [id*='advertisement'], ins.adsbygoogle"
        ).remove()
        return contentElement.html().trim().takeIf { it.isNotBlank() }
    }
}
