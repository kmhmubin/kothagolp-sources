package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class RanobesProvider : MainProvider() {

    override val name = "Ranobes"
    override val mainUrl = "https://ranobes.top"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=ranobes.top&sz=64"
    override val hasMainPage = true

    private fun extractCoverFromStyle(element: Element?): String? {
        val style = element?.attrOrNull("style") ?: return null
        return Regex("""url\((.*?)\)""").find(style)?.groupValues?.getOrNull(1)
            ?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseNovelElements(document: Document): List<Novel> {
        return document.select("[class*=short-cont]").mapNotNull { element ->
            val titleLink = element.selectFirstOrNull("h2.title > a") ?: return@mapNotNull null
            val name = titleLink.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = titleLink.attrOrNull("href") ?: return@mapNotNull null
            val novelUrl = fixUrl(href) ?: return@mapNotNull null
            val posterUrl = element.selectFirstOrNull("figure[style*=url]")
                ?.let { extractCoverFromStyle(it) }
                ?.let { fixUrl(it) }
            Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
        }
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val url = "$mainUrl/novels/page/$page/"
        val document = get(url).document
        val novels = parseNovelElements(document)
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search/$encodedQuery/page/1"
        val document = get(url).document
        return parseNovelElements(document)
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val name = document.selectFirstOrNull("h1")?.textOrNull()?.trim() ?: return null
        val posterUrl = document.selectFirstOrNull("div.poster > a > img")?.attrOrNull("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val synopsis = document.select("div.moreless.cont-text > *")
            .joinToString("\n") { it.text() }.trim().takeIf { it.isNotBlank() }
        val author = document.selectFirstOrNull("[itemprop=creator]")?.textOrNull()?.trim()
        val statusRaw = document.selectFirstOrNull("li[title*='Original status'] > a")?.textOrNull()?.trim()
        val status = when (statusRaw?.lowercase()) {
            "ongoing" -> "Ongoing"
            "completed" -> "Completed"
            else -> statusRaw
        }
        val tags = document.select("[id=mc-fs-genre] > a").mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotBlank() }
        val novelId = extractNovelId(fullUrl)
        val chapters = loadAllChapters(novelId)
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private fun extractNovelId(url: String): String? {
        return Regex("/novels/(\\d+)-").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("/(\\d+)-[^/]+/?$").find(url)?.groupValues?.getOrNull(1)
    }

    private suspend fun loadAllChapters(novelId: String?): List<Chapter> {
        if (novelId.isNullOrBlank()) return emptyList()
        val chapters = mutableListOf<Chapter>()
        try {
            val firstPageUrl = "$mainUrl/chapters/$novelId/page/1/"
            val firstPageText = get(firstPageUrl).text
            val dataMatch = Regex("""window\.__DATA__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                .find(firstPageText)
            if (dataMatch != null) {
                val json = JSONObject(dataMatch.groupValues[1])
                val pagesCount = json.optInt("pages_count", 1)
                parseChaptersFromJson(json, chapters)
                for (page in 2..pagesCount) {
                    try {
                        val pageText = get("$mainUrl/chapters/$novelId/page/$page/").text
                        val pageMatch = Regex("""window\.__DATA__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                            .find(pageText)
                        if (pageMatch != null) parseChaptersFromJson(JSONObject(pageMatch.groupValues[1]), chapters)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return chapters
    }

    private fun parseChaptersFromJson(json: JSONObject, chapters: MutableList<Chapter>) {
        val chaptersArray = json.optJSONArray("chapters") ?: return
        for (i in 0 until chaptersArray.length()) {
            val obj = chaptersArray.optJSONObject(i) ?: continue
            val title = obj.optString("title", null) ?: continue
            val link = obj.optString("link", null)?.takeIf { it.isNotBlank() } ?: continue
            val date = obj.optString("date", null)
            val chapterUrl = fixUrl(link) ?: continue
            chapters.add(Chapter(name = title, url = chapterUrl, dateOfRelease = date))
        }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val text = get(fullUrl).text
        val startTag = "<div class=\"text\" id=\"arrticle\">"
        val endTag = "<div class=\"category grey ellipses\">"
        val startIndex = text.indexOf(startTag).takeIf { it >= 0 } ?: return null
        val endIndex = text.indexOf(endTag, startIndex).takeIf { it >= 0 } ?: text.length
        val rawHtml = text.substring(startIndex + startTag.length, endIndex).trim()
        return rawHtml.ifBlank { null }
    }
}
