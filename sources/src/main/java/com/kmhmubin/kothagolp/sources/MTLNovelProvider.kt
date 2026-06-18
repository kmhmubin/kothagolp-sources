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

class MTLNovelProvider : MainProvider() {

    override val name = "MTL Novel"
    override val mainUrl = "https://www.mtlnovel.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=mtlnovel.com&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Rating", "rating/desc"),
        FilterOption("Latest", "date/desc"),
        FilterOption("Title", "title/asc")
    )

    private val altUsedHeader = mapOf("Alt-Used" to "www.mtlnovel.com")

    private fun buildBrowseUrl(page: Int, orderBy: String?): String {
        val parts = orderBy?.split("/") ?: listOf("rating", "desc")
        val orderPart = parts.getOrNull(0) ?: "rating"
        val orderDirection = parts.getOrNull(1) ?: "desc"
        return "$mainUrl/novel-list/?orderby=$orderPart&order=$orderDirection&status=all&pg=$page"
    }

    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val url = buildBrowseUrl(page, orderBy)
        val document = get(url, altUsedHeader).document
        val novels = document.select("div.box.wide").mapNotNull { parseNovelElement(it) }
        return MainPageResult(url = url, novels = novels)
    }

    private fun parseNovelElement(element: Element): Novel? {
        val titleLink = element.selectFirstOrNull("a.list-title") ?: return null
        val name = titleLink.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = titleLink.attrOrNull("href") ?: return null
        val novelUrl = href.removePrefix(mainUrl).let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }
        val posterUrl = element.selectFirstOrNull("amp-img[src]")?.attrOrNull("src")
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/wp-admin/admin-ajax.php?action=autosuggest&q=$encodedQuery"
        val response = get(url, altUsedHeader)
        return try {
            val json = JSONObject(response.text)
            val items = json.optJSONArray("items") ?: return emptyList()
            val results = items.optJSONObject(0)?.optJSONArray("results") ?: return emptyList()
            val novels = mutableListOf<Novel>()
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val rawTitle = obj.optString("title", null) ?: continue
                val title = stripHtmlTags(rawTitle).trim().takeIf { it.isNotBlank() } ?: continue
                val novelUrl = obj.optString("permalink", null)?.takeIf { it.isNotBlank() } ?: continue
                val thumbnail = obj.optString("thumbnail", null)?.takeIf { it.isNotBlank() }
                novels.add(Novel(name = title, url = novelUrl, posterUrl = thumbnail, apiName = this.name))
            }
            novels
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl, altUsedHeader).document
        val name = document.selectFirstOrNull("h1.entry-title")?.textOrNull()?.trim() ?: return null
        val posterUrl = document.selectFirstOrNull(".nov-head > amp-img[src]")?.attrOrNull("src")
        val synopsis = extractSynopsis(document)
        val author = extractInfoRow(document, "Author")
        val status = extractInfoRow(document, "Status")
        val tags = extractGenreTags(document)
        val slugPath = fullUrl.removePrefix(mainUrl).trimStart('/')
        val chapters = loadChapterList(slugPath)
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private fun extractSynopsis(document: Document): String? {
        val descDiv = document.selectFirstOrNull("div.desc") ?: return null
        val h2 = descDiv.selectFirstOrNull("h2")
        if (h2 != null) {
            val next = h2.nextElementSibling()
            val text = next?.textOrNull()?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return descDiv.select("p").joinToString("\n\n") { it.text() }.trim().takeIf { it.isNotBlank() }
    }

    private fun extractInfoRow(document: Document, label: String): String? {
        for (row in document.select(".info tr")) {
            val cells = row.select("td")
            if (cells.size >= 2 && cells[0].textOrNull()?.trim() == label) {
                return cells[cells.size - 1].textOrNull()?.trim()
            }
        }
        return null
    }

    private fun extractGenreTags(document: Document): List<String> {
        val tags = mutableListOf<String>()
        for (row in document.select(".info tr")) {
            val cells = row.select("td")
            if (cells.size >= 2) {
                val label = cells[0].textOrNull()?.trim()
                if (label == "Genre" || label == "Tags") {
                    cells[cells.size - 1].textOrNull()?.split(",")
                        ?.mapNotNull { it.trim().takeIf { s -> s.isNotBlank() } }
                        ?.let { tags.addAll(it) }
                }
            }
        }
        return tags
    }

    private suspend fun loadChapterList(novelSlug: String): List<Chapter> {
        return try {
            val chapterListUrl = "$mainUrl/$novelSlug/chapter-list/"
            val document = get(chapterListUrl, altUsedHeader + mapOf("Referer" to "$mainUrl/")).document
            val chapters = document.select("div.ch-list a.ch-link").mapNotNull { element ->
                val href = element.attrOrNull("href") ?: return@mapNotNull null
                val chapterUrl = href.removePrefix(mainUrl).let { path ->
                    if (path.startsWith("http")) path else "$mainUrl$path"
                }
                val chapterName = element.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Chapter(name = chapterName, url = chapterUrl)
            }
            chapters.reversed()
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl, altUsedHeader + mapOf("Referer" to "$mainUrl/")).document
        val contentElement = document.selectFirstOrNull("div.par") ?: return null
        contentElement.select(".adsbygoogle, script, style, iframe").remove()
        return contentElement.html()
    }
}
