package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ReadHiveProvider : MainProvider() {

    override val name = "ReadHive"
    override val mainUrl = "https://readhive.org"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=readhive.org&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Popular", "popular"),
        FilterOption("Latest", "latest"),
        FilterOption("New", "new"),
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
        val elements = document.select("div.novel-item, div.book-item")
            .takeIf { it.isNotEmpty() }
            ?: document.select("div.container div.row .col")
        return elements.mapNotNull { parseNovelElement(it) }
    }

    private fun parseNovelElement(element: Element): Novel? {
        val titleElement = element.selectFirstOrNull("h3 > a")
            ?: element.selectFirstOrNull(".title > a")
            ?: element.selectFirstOrNull("h4 > a")
            ?: element.selectFirstOrNull("a[href]")
            ?: return null
        val name = titleElement.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = titleElement.attrOrNull("href") ?: return null
        val novelUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return null
        val imgElement = element.selectFirstOrNull("img")
        val posterUrl = fixPosterUrl(imgElement)
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val sort = orderBy.takeUnless { it.isNullOrEmpty() } ?: "popular"
        val url = "$mainUrl/novels?page=$page&sort=$sort"
        val document = get(url).document
        val novels = parseNovels(document)
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?q=$encodedQuery"
        val document = get(url).document
        return parseNovels(document)
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document

        val name = document.selectFirstOrNull("h1.novel-title")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("h1")?.textOrNull()?.trim()
            ?: return null

        val imgElement = document.selectFirstOrNull("div.novel-cover img")
            ?: document.selectFirstOrNull(".book-cover img")
            ?: document.selectFirstOrNull(".cover img")
        val posterUrl = fixPosterUrl(imgElement)

        val author = document.selectFirstOrNull("div.info a[href*=author]")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull(".author-name")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("a[href*=/author/]")?.textOrNull()?.trim()

        val synopsis = document.select("div.summary > p, div.description > p, div.synopsis > p")
            .mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
            ?: document.selectFirstOrNull("div.synopsis, div.summary, div.description")
                ?.textOrNull()?.trim()

        val tags = document.select("a[href*=/genre/], a[href*=/tag/]")
            .mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val status = document.selectFirstOrNull(".status, span[class*=status], div[class*=status]")
            ?.textOrNull()?.trim()

        val chapters = loadChapters(document, fullUrl)

        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private suspend fun loadChapters(document: Document, novelUrl: String): List<Chapter> {
        val ajaxChapters = tryLoadChaptersViaAjax(document)
        if (ajaxChapters.isNotEmpty()) return ajaxChapters
        return loadChaptersFromHtml(document)
    }

    private suspend fun tryLoadChaptersViaAjax(document: Document): List<Chapter> {
        return try {
            val novelId = document.selectFirstOrNull("div.novel-content[data-id]")
                ?.attrOrNull("data-id")
                ?: document.selectFirstOrNull("[data-novel-id]")?.attrOrNull("data-novel-id")
                ?: document.selectFirstOrNull("[data-id]")?.attrOrNull("data-id")
                ?: return emptyList()
            val ajaxUrl = "$mainUrl/ajax/chapters?novelId=$novelId"
            val response = get(ajaxUrl)
            val chapters = mutableListOf<Chapter>()
            try {
                val jsonArray = JSONArray(response.text)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val chapterUrl = obj.optString("url", "").takeIf { it.isNotBlank() } ?: continue
                    val chapterName = obj.optString("name", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("title", "Chapter ${i + 1}")
                    val fullUrl = if (chapterUrl.startsWith("http")) chapterUrl else fixUrl(chapterUrl) ?: continue
                    chapters.add(Chapter(name = chapterName, url = fullUrl))
                }
            } catch (_: Exception) {
                val parsed = response.document
                parsed.select(".chapter-list li > a, ul.chapters li > a").forEach { element ->
                    val href = element.attrOrNull("href") ?: return@forEach
                    val chapterUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return@forEach
                    val chapterName = element.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
                        ?: "Chapter ${chapters.size + 1}"
                    chapters.add(Chapter(name = chapterName, url = chapterUrl))
                }
            }
            chapters
        } catch (_: Exception) { emptyList() }
    }

    private fun loadChaptersFromHtml(document: Document): List<Chapter> {
        return document.select(".chapter-list li > a, ul.chapters li > a, .list-chapter li > a")
            .mapNotNull { element ->
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
            val contentElement = document.selectFirstOrNull("div.chapter-content")
                ?: document.selectFirstOrNull("div#content")
                ?: document.selectFirstOrNull("div.content")
                ?: return null
            contentElement.select(".adsbygoogle, script, style, ins, [class*=ads], [id*=ads]").remove()
            cleanChapterHtml(contentElement.html())
        } catch (_: Exception) { null }
    }
}
