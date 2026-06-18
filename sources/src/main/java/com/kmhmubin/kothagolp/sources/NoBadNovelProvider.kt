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

class NoBadNovelProvider : MainProvider() {

    override val name = "NoBadNovel"
    override val mainUrl = "https://nobadnovel.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=nobadnovel.com&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Most Popular", "most-popular"),
        FilterOption("Latest", "latest-release-novel"),
        FilterOption("New", "new-novel")
    )

    private fun fixPosterUrl(imgElement: Element?): String? {
        if (imgElement == null) return null
        val rawSrc = imgElement.attrOrNull("data-src")
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
        val rows = document.select("div.col-novel-main .list-truyen div.row")
            .takeIf { it.isNotEmpty() }
            ?: document.select("ul.list-truyen li").takeIf { it.isNotEmpty() }
            ?: document.select("div.list-novel .row")
        return rows.mapNotNull { parseNovelElement(it) }
    }

    private fun parseNovelElement(element: Element): Novel? {
        val titleElement = element.selectFirstOrNull("h3.truyen-title > a")
            ?: element.selectFirstOrNull("h3 > a")
            ?: element.selectFirstOrNull(".novel-title > a")
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
        val sort = orderBy.takeUnless { it.isNullOrEmpty() } ?: "most-popular"
        val url = "$mainUrl/$sort?page=$page"
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

        val name = document.selectFirstOrNull("h3.title")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("h1.novel-title")?.textOrNull()?.trim()
            ?: return null

        val imgElement = document.selectFirstOrNull(".book > img")
            ?: document.selectFirstOrNull(".novel-cover > img")
            ?: document.selectFirstOrNull("div.book img")
        val posterUrl = fixPosterUrl(imgElement)

        val author = document.selectFirstOrNull("div.info-holder a[href*=/author/]")?.textOrNull()?.trim()
            ?: extractInfoField(document, "Author")

        val synopsis = document.selectFirstOrNull("div.desc-text")?.textOrNull()?.trim()
            ?: document.select("div.description > p")
                .mapNotNull { it.textOrNull()?.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .takeIf { it.isNotBlank() }

        val status = document.selectFirstOrNull(".info > li:contains(Status) > a")?.textOrNull()?.trim()
            ?: extractInfoField(document, "Status")

        val tags = document.select(".info > li:contains(Category) > a, .info > li:contains(Genre) > a")
            .mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val chapters = loadChapters(document, fullUrl)

        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private fun extractInfoField(document: Document, fieldName: String): String? {
        return document.select(".info > li").firstOrNull { li ->
            li.text().contains(fieldName, ignoreCase = true)
        }?.selectFirstOrNull("a")?.textOrNull()?.trim()
    }

    private suspend fun loadChapters(document: Document, novelUrl: String): List<Chapter> {
        val ajaxChapters = tryLoadChaptersViaAjax(document)
        if (ajaxChapters.isNotEmpty()) return ajaxChapters
        return loadChaptersFromHtml(document)
    }

    private suspend fun tryLoadChaptersViaAjax(document: Document): List<Chapter> {
        return try {
            val novelId = document.selectFirstOrNull("select#list-chapter[data-novel-id]")
                ?.attrOrNull("data-novel-id")
                ?: document.selectFirstOrNull("[data-novel-id]")?.attrOrNull("data-novel-id")
                ?: return emptyList()
            val ajaxUrl = "$mainUrl/ajax/chapter-option?novelId=$novelId"
            val response = get(ajaxUrl)
            val parsed = Jsoup.parse(response.text)
            parsed.select("option").mapNotNull { option ->
                val value = option.attrOrNull("value") ?: return@mapNotNull null
                val chapterUrl = if (value.startsWith("http")) value else fixUrl(value) ?: return@mapNotNull null
                val chapterName = option.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Chapter(name = chapterName, url = chapterUrl)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun loadChaptersFromHtml(document: Document): List<Chapter> {
        return document.select("ul.list-chapter li a").mapNotNull { element ->
            val href = element.attrOrNull("href") ?: return@mapNotNull null
            val chapterUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return@mapNotNull null
            val chapterName = element.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Chapter(name = chapterName, url = chapterUrl)
        }
    }

    override suspend fun loadChapterContent(url: String): String? {
        return try {
            val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
            val document = get(fullUrl).document
            val contentElement = document.selectFirstOrNull("#chapter-content") ?: return null
            contentElement.select(".adsbygoogle, script, style, ins, [class*=ads]").remove()
            cleanChapterHtml(contentElement.html())
        } catch (_: Exception) { null }
    }
}
