package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WattpadProvider : MainProvider() {

    override val name = "Wattpad"
    override val mainUrl = "https://www.wattpad.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=wattpad.com&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Featured", "featured"),
        FilterOption("Fanfiction", "fanfiction"),
        FilterOption("Romance", "romance"),
        FilterOption("Science Fiction", "science-fiction")
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    private fun parseStoryCard(element: Element): Novel? {
        val href = element.attrOrNull("href") ?: return null
        val novelUrl = fixUrl(href) ?: return null
        val name = element.selectFirstOrNull(".story-card-data > .story-info > .sr-only")?.textOrNull()?.trim()
            ?: element.selectFirstOrNull("[class*=title]")?.textOrNull()?.trim()
            ?: return null
        val posterUrl = element.selectFirstOrNull(".story-card-data > .cover > img")?.attrOrNull("src")
            ?: element.selectFirstOrNull("img")?.attrOrNull("src")
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    private fun parseStoryCards(document: Document): List<Novel> {
        return document.select(".story-card[href]").mapNotNull { parseStoryCard(it) }
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val category = orderBy?.takeUnless { it.isNullOrEmpty() } ?: "featured"
        val url = "$mainUrl/stories/$category"
        val document = get(url, defaultHeaders).document
        val novels = parseStoryCards(document)
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search/$encodedQuery"
        val document = get(url, defaultHeaders).document
        return parseStoryCards(document)
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl, defaultHeaders).document
        val name = document.selectFirstOrNull(".story-info > .sr-only")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull(".item-title")?.textOrNull()?.trim()
            ?: return null
        val posterUrl = document.selectFirstOrNull(".story-cover > img")?.attrOrNull("src")
        val author = document.selectFirstOrNull(".author-info__username > a")?.textOrNull()?.trim()
        val synopsis = document.selectFirstOrNull(".description-text")?.textOrNull()?.trim()
        val tags = document.select("ul.tag-items > li > a").mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotBlank() }
        val chapters = document.select(".story-parts > ul > li > a").mapNotNull { element ->
            val href = element.attrOrNull("href") ?: return@mapNotNull null
            val chapterUrl = fixUrl(href) ?: return@mapNotNull null
            val chapterName = element.selectFirstOrNull(".part__label")?.textOrNull()?.trim()
                ?: element.textOrNull()?.trim()
                ?: return@mapNotNull null
            Chapter(name = chapterName, url = chapterUrl)
        }
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }
        )
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val response = get(fullUrl, defaultHeaders)
        val text = response.text
        val prefetchedContent = tryExtractPrefetched(text, fullUrl)
        if (prefetchedContent != null) return prefetchedContent
        return response.document.selectFirstOrNull("pre")?.html()
    }

    private suspend fun tryExtractPrefetched(text: String, chapterUrl: String): String? {
        return try {
            val match = Regex("""window\.prefetched\s*=\s*(.+?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(text) ?: return null
            val json = JSONObject(match.groupValues[1].trim().trimEnd(';'))
            var textUrl: String? = null
            for (key in json.keys()) {
                val entry = json.optJSONObject(key) ?: continue
                val candidate = entry.optJSONObject("data")
                    ?.optJSONObject("text_url")
                    ?.optString("text", null)
                if (!candidate.isNullOrBlank()) {
                    textUrl = candidate
                    break
                }
            }
            if (textUrl == null) return null
            val contentBuilder = StringBuilder()
            var page = 1
            while (true) {
                val pageUrl = if (page == 1) textUrl else "${textUrl.substringBeforeLast('/')}-$page"
                val pageResponse = get(pageUrl, mapOf("User-Agent" to "Mozilla/5.0"))
                val pageText = pageResponse.text
                if (pageText.length < 30) break
                contentBuilder.append(pageText)
                page++
            }
            val content = contentBuilder.toString().trim()
            content.ifBlank { null }
        } catch (_: Throwable) { null }
    }
}
