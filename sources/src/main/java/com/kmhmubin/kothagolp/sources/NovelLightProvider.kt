package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NovelLightProvider : MainProvider() {

    override val name = "NovelLight"
    override val mainUrl = "https://novelight.net"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=novelight.net&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Popular", "popularity"),
        FilterOption("Latest Updated", "-time_updated")
    )

    private val bookIdRegex = Regex("""const OBJECT_BY_COMMENT = (\d+)""")
    private val csrfRegex = Regex("""window\.CSRF_TOKEN = "([^"]+)"""")
    private val chapterIdRegex = Regex("""const CHAPTER_ID = "(\d+)"""")

    private fun fixPosterUrl(imgElement: Element?): String? {
        if (imgElement == null) return null
        val rawSrc = imgElement.attrOrNull("data-src") ?: imgElement.attrOrNull("src") ?: return null
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
        return document.select("a.item").mapNotNull { element ->
            val name = element.selectFirstOrNull("div.title")?.textOrNull()?.trim()
                ?: return@mapNotNull null
            val href = element.attrOrNull("href") ?: return@mapNotNull null
            val novelUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            val imgElement = element.selectFirstOrNull("img")
            val posterUrl = fixPosterUrl(imgElement)
            Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
        }
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val ordering = orderBy.takeUnless { it.isNullOrEmpty() } ?: "popularity"
        val url = "$mainUrl/catalog/?ordering=$ordering&page=$page"
        val document = get(url).document
        val novels = parseNovels(document)
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/catalog/?search=$encodedQuery"
        val document = get(url).document
        return parseNovels(document)
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val response = get(fullUrl)
        val document = response.document
        val pageText = response.text

        val name = document.selectFirstOrNull("h1")?.textOrNull()?.trim() ?: return null

        val imgElement = document.selectFirstOrNull("div.poster > img")
        val posterUrl = fixPosterUrl(imgElement)

        val synopsis = document.selectFirstOrNull("section.text-info.section > p")?.textOrNull()?.trim()

        val author = document.selectFirstOrNull(
            "div.mini-info .item:has(.sub-header:contains(Author)) div.info"
        )?.textOrNull()?.trim()

        val status = document.selectFirstOrNull(
            "div.mini-info .item:has(.sub-header:contains(Status)) div.info"
        )?.textOrNull()?.trim()

        val tags = document.select(
            "div.mini-info .item:has(.sub-header:contains(Genres)) div.info > a"
        ).mapNotNull { it.textOrNull()?.trim() }.filter { it.isNotBlank() }

        val chapters = loadAllChapters(document, pageText, fullUrl)

        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private suspend fun loadAllChapters(
        document: Document, pageText: String, novelUrl: String
    ): List<Chapter> {
        return try {
            val totalPages = document.select("#select-pagination-chapter > option").size.coerceAtLeast(1)
            val bookId = bookIdRegex.find(pageText)?.groupValues?.getOrNull(1) ?: return emptyList()
            val csrfToken = csrfRegex.find(pageText)?.groupValues?.getOrNull(1) ?: return emptyList()

            val allChapters = mutableListOf<List<Chapter>>()
            for (pageNum in 1..totalPages) {
                val pageChapters = loadChapterPage(bookId, csrfToken, pageNum, novelUrl)
                allChapters.add(pageChapters)
            }

            val result = mutableListOf<Chapter>()
            for (pageChapters in allChapters.reversed()) {
                result.addAll(pageChapters.reversed())
            }
            result
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun loadChapterPage(
        bookId: String, csrfToken: String, pageNum: Int, novelUrl: String
    ): List<Chapter> {
        return try {
            val ajaxUrl = "$mainUrl/book/ajax/chapter-pagination"
            val response = post(
                url = ajaxUrl,
                data = mapOf(
                    "csrfmiddlewaretoken" to csrfToken,
                    "book_id" to bookId,
                    "page" to pageNum.toString()
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to novelUrl
                )
            )
            val json = JSONObject(response.text)
            val html = json.optString("html", "")
            if (html.isBlank()) return emptyList()
            val parsed = Jsoup.parse(html)
            parsed.select("a").mapNotNull { element ->
                val href = element.attrOrNull("href") ?: return@mapNotNull null
                val chapterUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val chapterName = element.selectFirstOrNull(".title")?.textOrNull()?.trim()
                    ?: element.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val date = element.selectFirstOrNull(".date")?.textOrNull()?.trim()
                Chapter(name = chapterName, url = chapterUrl, dateOfRelease = date)
            }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun loadChapterContent(url: String): String? {
        return try {
            val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
            val response = get(fullUrl)
            val pageText = response.text

            val csrfToken = csrfRegex.find(pageText)?.groupValues?.getOrNull(1) ?: return null
            val chapterId = chapterIdRegex.find(pageText)?.groupValues?.getOrNull(1) ?: return null

            val ajaxUrl = "$mainUrl/book/ajax/read-chapter/$chapterId"
            val ajaxResponse = get(
                url = ajaxUrl,
                headers = mapOf(
                    "Cookie" to "csrftoken=$csrfToken",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to fullUrl
                )
            )

            val json = JSONObject(ajaxResponse.text)
            val contentHtml = json.optString("content", "")
            val divClass = json.optString("class", "")
            if (contentHtml.isBlank()) return null

            val parsed = Jsoup.parse(contentHtml)
            if (divClass.isNotBlank()) parsed.select(".$divClass").remove()
            parsed.select(".adsbygoogle, script, style").remove()

            cleanChapterHtml(parsed.body()?.html() ?: parsed.html())
        } catch (_: Exception) { null }
    }
}
