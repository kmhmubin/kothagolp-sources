package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GrayCityProvider : MainProvider() {

    override val name = "GrayCity"
    override val mainUrl = "https://graycity.net"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=graycity.net&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Trending", "trending"),
        FilterOption("Latest", "latest"),
        FilterOption("New", "new-manga"),
        FilterOption("Rating", "rating")
    )

    private fun fixPosterUrl(imgElement: Element?): String? {
        if (imgElement == null) return null
        val rawSrc = imgElement.attrOrNull("data-src")
            ?: imgElement.attrOrNull("data-lazy-src")
            ?: imgElement.attrOrNull("src") ?: return null
        if (rawSrc.isBlank() || rawSrc.contains("data:image")) return null
        return if (rawSrc.startsWith("http")) rawSrc else "$mainUrl$rawSrc"
    }

    private fun cleanChapterHtml(html: String): String {
        var cleaned = html
        cleaned = cleaned.replace("&nbsp;", " ")
        cleaned = cleaned.replace(Regex("\\s{3,}"), "\n\n")
        cleaned = cleaned.replace(Regex("(<br\\s*/?>\\s*){3,}"), "<br/><br/>")
        return cleaned.trim()
    }

    private fun parseNovels(document: Document): List<Novel> {
        val elements = document.select("div.page-item-detail, div.c-tabs-item__content")
        if (elements.isNotEmpty()) return elements.mapNotNull { parseMadaraElement(it) }
        return document.select("div.col-12.col-md-6.badge-pos-1").mapNotNull { parseMadaraElement(it) }
    }

    private fun parseMadaraElement(element: Element): Novel? {
        val titleElement = element.selectFirstOrNull("h3.h5 > a")
            ?: element.selectFirstOrNull("h3 > a")
            ?: element.selectFirstOrNull("h4 > a")
            ?: return null
        val name = titleElement.textOrNull()?.trim() ?: return null
        val href = titleElement.attrOrNull("href") ?: return null
        val novelUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return null
        val imgElement = element.selectFirstOrNull("img")
        val posterUrl = fixPosterUrl(imgElement)
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val orderByValue = orderBy.takeUnless { it.isNullOrEmpty() } ?: "trending"
        val url = "$mainUrl/page/$page/?s=&post_type=wp-manga&m_orderby=$orderByValue"
        val document = get(url).document
        val novels = parseNovels(document)
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/page/1/?s=$encodedQuery&post_type=wp-manga"
        val document = get(url).document
        return parseNovels(document)
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document

        val name = document.selectFirstOrNull("div.post-title h1")
            ?.textOrNull()?.trim() ?: return null

        val imgElement = document.selectFirstOrNull("div.summary_image img")
        val posterUrl = fixPosterUrl(imgElement)

        val author = document.selectFirstOrNull("div.author-content > a")?.textOrNull()?.trim()

        val synopsis = document.select("div.description-summary p")
            .mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

        val tags = document.select("div.genres-content > a")
            .mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotBlank() }

        val status = document.select("div.post-status .post-content_item").firstOrNull { item ->
            item.selectFirstOrNull(".summary-heading")?.text()?.contains("Status", ignoreCase = true) == true
        }?.selectFirstOrNull(".summary-content")?.textOrNull()?.trim()

        val chapters = loadChapters(fullUrl)

        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private suspend fun loadChapters(novelUrl: String): List<Chapter> {
        return try {
            val ajaxUrl = "$novelUrl/ajax/chapters/"
            val response = post(url = ajaxUrl, data = emptyMap())
            val document = response.document
            document.select("li.wp-manga-chapter > a").mapNotNull { element ->
                val href = element.attrOrNull("href") ?: return@mapNotNull null
                val chapterUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return@mapNotNull null
                val chapterName = element.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val date = element.parent()
                    ?.selectFirstOrNull("span.chapter-release-date > i")
                    ?.textOrNull()?.trim()
                Chapter(name = chapterName, url = chapterUrl, dateOfRelease = date)
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun loadChapterContent(url: String): String? {
        return try {
            val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
            val document = get(fullUrl).document
            val contentElement = document.selectFirstOrNull("div.reading-content")
                ?: document.selectFirstOrNull("div.text-left")
                ?: return null
            contentElement.select(".adsbygoogle, script, style, ins, [class*=ads]").remove()
            val paragraphs = contentElement.select("p")
            val html = if (paragraphs.isNotEmpty()) {
                paragraphs.joinToString("<br>") { it.html() }
            } else {
                contentElement.html()
            }
            cleanChapterHtml(html)
        } catch (_: Exception) { null }
    }
}
