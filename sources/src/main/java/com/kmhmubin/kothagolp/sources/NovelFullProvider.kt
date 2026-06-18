package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NovelFullProvider : MainProvider() {

    override val name = "NovelFull"
    override val mainUrl = "https://novelfull.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=novelfull.com&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Most Popular", "most-popular"),
        FilterOption("Hot Novel", "hot-novel"),
        FilterOption("Completed", "completed-novel"),
        FilterOption("Latest Release", "latest-release-novel")
    )

    override val tags = listOf(
        FilterOption("All", ""),
        FilterOption("Action", "action"),
        FilterOption("Adventure", "adventure"),
        FilterOption("Comedy", "comedy"),
        FilterOption("Drama", "drama"),
        FilterOption("Fantasy", "fantasy"),
        FilterOption("Harem", "harem"),
        FilterOption("Historical", "historical"),
        FilterOption("Horror", "horror"),
        FilterOption("Josei", "josei"),
        FilterOption("Martial Arts", "martial-arts"),
        FilterOption("Mature", "mature"),
        FilterOption("Mecha", "mecha"),
        FilterOption("Mystery", "mystery"),
        FilterOption("Psychological", "psychological"),
        FilterOption("Romance", "romance"),
        FilterOption("School Life", "school-life"),
        FilterOption("Sci-fi", "sci-fi"),
        FilterOption("Seinen", "seinen"),
        FilterOption("Shoujo", "shoujo"),
        FilterOption("Shounen", "shounen"),
        FilterOption("Slice of Life", "slice-of-life"),
        FilterOption("Supernatural", "supernatural"),
        FilterOption("Tragedy", "tragedy"),
        FilterOption("Wuxia", "wuxia"),
        FilterOption("Xianxia", "xianxia"),
        FilterOption("Xuanhuan", "xuanhuan"),
        FilterOption("Yaoi", "yaoi"),
        FilterOption("Smut", "smut"),
        FilterOption("Adult", "adult")
    )

    private fun parseNovelElement(element: Element): Novel? {
        val titleLink = element.selectFirstOrNull("h3.truyen-title > a") ?: return null
        val name = titleLink.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = titleLink.attrOrNull("href") ?: return null
        val novelUrl = fixUrl(href) ?: return null
        val posterUrl = element.selectFirstOrNull(".col-xs-3 > a > img")?.attrOrNull("src")
            ?.let { fixUrl(it) }
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    private fun parseNovels(document: Document): List<Novel> {
        return document.select("div.col-novel-main .list-truyen div.row").mapNotNull { parseNovelElement(it) }
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val url = when {
            !tag.isNullOrEmpty() -> "$mainUrl/genre/$tag?page=$page"
            else -> {
                val sort = orderBy?.takeUnless { it.isNullOrEmpty() } ?: "most-popular"
                "$mainUrl/$sort?page=$page"
            }
        }
        val document = get(url).document
        val novels = parseNovels(document)
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?keyword=$encodedQuery&page=1"
        val document = get(url).document
        return parseNovels(document)
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val name = document.selectFirstOrNull("h3.title")?.textOrNull()?.trim() ?: return null
        val posterUrl = document.selectFirstOrNull(".book > img")?.attrOrNull("src")?.let { fixUrl(it) }
        val author = document.selectFirstOrNull("div.info-holder a[href*=/author/]")?.textOrNull()?.trim()
        val synopsis = document.selectFirstOrNull("div.desc-text")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("div.desc")?.textOrNull()?.trim()
        val statusText = document.selectFirstOrNull("div.info-holder div.info > div:last-child > a")
            ?.textOrNull()?.trim()
        val status = when (statusText?.lowercase()) {
            "ongoing" -> "Ongoing"
            "completed" -> "Completed"
            else -> statusText
        }
        val tags = document.select("div.info-holder div.info > div:nth-child(3) a")
            .mapNotNull { it.textOrNull()?.trim() }.filter { it.isNotBlank() }
        val novelId = document.selectFirstOrNull("select#list-chapter[data-novel-id]")
            ?.attrOrNull("data-novel-id")
        val chapters = loadChaptersViaAjax(novelId)
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private suspend fun loadChaptersViaAjax(novelId: String?): List<Chapter> {
        if (novelId.isNullOrBlank()) return emptyList()
        return try {
            val ajaxUrl = "$mainUrl/ajax/chapter-option?novelId=$novelId"
            val responseText = get(ajaxUrl).text
            val parsed = Jsoup.parse(responseText)
            parsed.select("option[value]").mapNotNull { option ->
                val chapterUrl = option.attrOrNull("value")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val chapterName = option.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Chapter(name = chapterName, url = fixUrl(chapterUrl) ?: return@mapNotNull null)
            }
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val contentElement = document.selectFirstOrNull("div#chapter-content") ?: return null
        contentElement.select(".adsbygoogle, script, style, iframe, [class*=ads]").remove()
        return contentElement.html()
    }
}
