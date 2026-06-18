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

class HiraethTranslationProvider : MainProvider() {

    override val name = "Hiraeth Translation"
    override val mainUrl = "https://hiraethtranslation.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=hiraethtranslation.com&sz=64"
    override val hasMainPage = true

    override val tags = listOf(
        FilterOption("Action", "action"),
        FilterOption("Adventure", "adventure"),
        FilterOption("Comedy", "comedy"),
        FilterOption("Drama", "drama"),
        FilterOption("Fantasy", "fantasy"),
        FilterOption("Historical", "historical"),
        FilterOption("Horror", "horror"),
        FilterOption("Josei", "josei"),
        FilterOption("Martial Arts", "martial-arts"),
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
        FilterOption("Xianxia", "xianxia")
    )

    override val orderBys = listOf(
        FilterOption("Latest", "latest"),
        FilterOption("A-Z", "alphabet"),
        FilterOption("Rating", "rating"),
        FilterOption("Trending", "trending"),
        FilterOption("Most Views", "views"),
        FilterOption("New", "new-manga")
    )

    private fun parseStatus(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        return when {
            statusText.contains("Ongoing", ignoreCase = true) -> "Ongoing"
            statusText.contains("Completed", ignoreCase = true) -> "Completed"
            statusText.contains("Hiatus", ignoreCase = true) -> "On Hiatus"
            else -> null
        }
    }

    private fun parseNovelFromElement(element: Element): Novel? {
        val titleEl = element.selectFirstOrNull("div.post-title > h3 > a")
            ?: element.selectFirstOrNull("h3.h5 > a")
            ?: element.selectFirstOrNull("div.post-title h3 a")
            ?: return null
        val name = titleEl.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = titleEl.attrOrNull("href") ?: return null
        val novelUrl = fixUrl(href) ?: return null
        val imgEl = element.selectFirstOrNull("img.img-responsive")
        val posterUrl = imgEl?.attrOrNull("data-src")?.ifBlank { null }
            ?: imgEl?.attrOrNull("src")
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl?.let { fixUrl(it) }, apiName = this.name)
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val order = orderBy.takeUnless { it.isNullOrEmpty() } ?: "latest"
        val url = if (!tag.isNullOrEmpty()) {
            "$mainUrl/manga-genre/$tag/page/$page/?s=&post_type=wp-manga&m_orderby=$order"
        } else {
            "$mainUrl/page/$page/?s=&post_type=wp-manga&m_orderby=$order"
        }
        val document = get(url).document
        val novels = document.select("div.c-tabs-item__content, div.page-item-detail")
            .mapNotNull { parseNovelFromElement(it) }
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/page/1/?s=$encodedQuery&post_type=wp-manga"
        val document = get(url).document
        return document.select("div.c-tabs-item__content, div.page-item-detail")
            .mapNotNull { parseNovelFromElement(it) }
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val name = document.selectFirstOrNull("div.post-title h1")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("div.post-title h3")?.textOrNull()?.trim()
            ?: return null
        val posterUrl = document.selectFirstOrNull("div.summary_image img")?.let { img ->
            (img.attrOrNull("data-src")?.ifBlank { null } ?: img.attrOrNull("src"))?.let { fixUrl(it) }
        }
        val author = document.selectFirstOrNull("div.author-content > a")?.textOrNull()?.trim()
        val synopsis = document.select("div.description-summary p")
            .joinToString("\n\n") { it.text() }.trim().takeIf { it.isNotBlank() }
        val genres = document.select("div.genres-content a").mapNotNull { it.textOrNull()?.trim() }
        val status = extractStatus(document)
        val chapters = loadChapters(fullUrl)
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = genres.ifEmpty { null }, status = status
        )
    }

    private fun extractStatus(document: Document): String? {
        val statusBlocks = document.select("div.post-status > div.post-content_item")
        for (block in statusBlocks) {
            val label = block.selectFirstOrNull("div.summary-heading > h5")?.textOrNull()?.trim() ?: continue
            if (label.contains("Status", ignoreCase = true)) {
                val value = block.selectFirstOrNull("div.summary-content")?.textOrNull()?.trim()
                return parseStatus(value)
            }
        }
        return null
    }

    private suspend fun loadChapters(novelUrl: String): List<Chapter> {
        return try {
            val ajaxUrl = novelUrl.trimEnd('/') + "/ajax/chapters/"
            val response = post(ajaxUrl)
            val doc = Jsoup.parse(response.text)
            doc.select("li.wp-manga-chapter > a").mapNotNull { el ->
                val href = el.attrOrNull("href") ?: return@mapNotNull null
                val chapterUrl = fixUrl(href) ?: return@mapNotNull null
                val chapterName = el.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val date = el.parent()?.selectFirstOrNull("span.chapter-release-date > i")?.textOrNull()?.trim()
                Chapter(name = chapterName, url = chapterUrl, dateOfRelease = date)
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val contentEl = document.selectFirstOrNull("div.reading-content") ?: return null
        contentEl.select("script, style, .ads, .adsbygoogle, [id*='ads'], [class*='ads'], ins").remove()
        return contentEl.html()
    }
}
