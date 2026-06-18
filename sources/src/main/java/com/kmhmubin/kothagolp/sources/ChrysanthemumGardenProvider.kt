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

class ChrysanthemumGardenProvider : MainProvider() {

    override val name = "Chrysanthemum Garden"
    override val mainUrl = "https://chrysanthemumgarden.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=chrysanthemumgarden.com&sz=64"
    override val hasMainPage = true

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val url = "$mainUrl/books?page=$page"
        val document = get(url).document
        val novels = document.select("div.entry-content > div > a").mapNotNull { element ->
            parseNovelFromBrowse(element)
        }
        return MainPageResult(url = url, novels = novels)
    }

    private fun parseNovelFromBrowse(element: Element): Novel? {
        val href = element.attrOrNull("href") ?: return null
        val novelUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return null
        val coverImg = element.selectFirstOrNull("div.image > img")
        val posterUrl = coverImg?.attrOrNull("src") ?: coverImg?.attrOrNull("data-src")
        val name = element.selectFirstOrNull("div.info > h2 > a")?.textOrNull()?.trim()
            ?: element.selectFirstOrNull("div.info > h2")?.textOrNull()?.trim()
            ?: return null
        if (name.isBlank()) return null
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/wp-json/cg/novels?search=$encodedQuery"
        val response = get(url)
        return try {
            val jsonArray = JSONArray(response.text)
            val novels = mutableListOf<Novel>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val slug = obj.optString("slug", null) ?: continue
                val title = obj.optString("title", null) ?: continue
                val coverUrl = obj.optString("cover_url", null)
                val novelUrl = "$mainUrl/novel-tl/$slug/"
                novels.add(Novel(name = title, url = novelUrl, posterUrl = coverUrl, apiName = this.name))
            }
            novels
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val name = document.selectFirstOrNull("h2.novel-title > a")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("h2.novel-title")?.textOrNull()?.trim()
            ?: return null
        val posterUrl = document.selectFirstOrNull("div.novel-cover > img")?.attrOrNull("src")
        val novelInfoDiv = document.selectFirstOrNull("div.novel-info")
        val author = novelInfoDiv?.select("p, div")?.firstOrNull { el ->
            el.textOrNull()?.contains("Author", ignoreCase = true) == true
        }?.textOrNull()?.substringAfter(":")?.trim()
        val synopsis = document.selectFirstOrNull("div.summary__content, div.description-summary, div.entry-content")
            ?.textOrNull()?.trim()
        val tags = document.select("div.genres-content a, div.tags-content a")
            .mapNotNull { it.textOrNull()?.trim() }.filter { it.isNotBlank() }
        val status = novelInfoDiv?.select("span")?.firstOrNull { span ->
            span.textOrNull()?.contains("Status", ignoreCase = true) == true ||
            span.parent()?.textOrNull()?.contains("Status", ignoreCase = true) == true
        }?.nextElementSibling()?.textOrNull()?.trim()
        val chapters = parseChapters(document)
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private fun parseChapters(document: Document): List<Chapter> {
        val chapterElements = document.select("div.chapter-item > a, li.chapter-item > a")
        if (chapterElements.isEmpty()) return emptyList()
        return chapterElements.mapNotNull { link ->
            val href = link.attrOrNull("href") ?: return@mapNotNull null
            val chapterUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return@mapNotNull null
            val chapterName = link.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Chapter(name = chapterName, url = chapterUrl)
        }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val contentElement = document.selectFirstOrNull("div#novel-content") ?: return null
        contentElement.select("script, style, .ads, .adsbygoogle, [class*='ads'], [id*='ads']").remove()
        return contentElement.html().trim().takeIf { it.isNotBlank() }
    }
}
