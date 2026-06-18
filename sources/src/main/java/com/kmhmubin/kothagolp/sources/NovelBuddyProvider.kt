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

class NovelBuddyProvider : MainProvider() {

    override val name = "NovelBuddy"
    override val mainUrl = "https://novelbuddy.io"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=novelbuddy.io&sz=64"
    override val hasMainPage = true

    private val apiBase = "https://api.novelbuddy.io"
    private val cdnBase = "https://cdn.novelbuddy.io/cdn-cgi/image/height=400,quality=100,format=auto/"

    private val jsonHeaders = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json"
    )

    private fun buildCoverUrl(cover: String?): String? {
        if (cover.isNullOrBlank()) return null
        return if (cover.startsWith("http")) cover else "$cdnBase$cover"
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val url = "$apiBase/titles?page=$page&gender=1"
        val response = get(url, jsonHeaders)
        val novels = try {
            val json = JSONObject(response.text)
            val dataArray = json.optJSONArray("data") ?: JSONArray()
            parseTitlesList(dataArray)
        } catch (_: Throwable) { emptyList() }
        return MainPageResult(url = url, novels = novels)
    }

    private fun parseTitlesList(dataArray: JSONArray): List<Novel> {
        val novels = mutableListOf<Novel>()
        for (i in 0 until dataArray.length()) {
            val obj = dataArray.optJSONObject(i) ?: continue
            val name = obj.optString("name", null) ?: continue
            val slug = obj.optString("slug", null) ?: continue
            val cover = obj.optString("cover", null)
            val posterUrl = buildCoverUrl(cover)
            val rating = obj.optDouble("rate", Double.NaN).takeIf { !it.isNaN() }
                ?.let { (it / 5.0 * 1000.0).toInt().coerceIn(0, 1000) }
            val novelUrl = "$mainUrl/$slug"
            novels.add(Novel(name = name, url = novelUrl, posterUrl = posterUrl, rating = rating, apiName = this.name))
        }
        return novels
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$apiBase/titles/search?name=$encodedQuery&page=1"
        val response = get(url, jsonHeaders)
        return try {
            val json = JSONObject(response.text)
            val dataArray = json.optJSONArray("data") ?: JSONArray()
            parseTitlesList(dataArray)
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val slug = fullUrl.trimEnd('/').substringAfterLast("/")
        val pageResponse = get(fullUrl)
        val pageDocument = pageResponse.document
        val novelId = extractNovelIdFromPage(pageDocument) ?: return null
        val detailsUrl = "$apiBase/titles/$novelId"
        val detailsResponse = get(detailsUrl, jsonHeaders)
        val detailsJson = try { JSONObject(detailsResponse.text) } catch (_: Throwable) { return null }
        val name = detailsJson.optString("name", null) ?: return null
        val cover = detailsJson.optString("cover", null)
        val posterUrl = buildCoverUrl(cover)
        val author = detailsJson.optString("author", null)
        val synopsis = detailsJson.optString("description", null)?.trim()
        val status = detailsJson.optString("status", null)?.replaceFirstChar { it.uppercase() }
        val genresArray = detailsJson.optJSONArray("genres")
        val tags = if (genresArray != null) {
            (0 until genresArray.length()).mapNotNull { idx ->
                when (val g = genresArray.opt(idx)) {
                    is String -> g.trim().takeIf { it.isNotBlank() }
                    is JSONObject -> g.optString("name", null)?.trim()?.takeIf { it.isNotBlank() }
                    else -> null
                }
            }
        } else emptyList()
        val chapters = loadChapters(novelId, slug)
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private fun extractNovelIdFromPage(document: Document): String? {
        return try {
            val scriptData = document.selectFirstOrNull("script#__NEXT_DATA__")?.data() ?: return null
            val root = JSONObject(scriptData)
            val props = root.optJSONObject("props") ?: return null
            val pageProps = props.optJSONObject("pageProps") ?: return null
            val novel = pageProps.optJSONObject("novel") ?: return null
            novel.optString("_id", null)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
    }

    private suspend fun loadChapters(novelId: String, slug: String): List<Chapter> {
        return try {
            val url = "$apiBase/titles/$novelId/chapters?offset=0&limit=999"
            val response = get(url, jsonHeaders)
            val json = JSONObject(response.text)
            val dataArray = json.optJSONArray("data") ?: JSONArray()
            val chapters = mutableListOf<Chapter>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.optJSONObject(i) ?: continue
                val chapterId = obj.optString("_id", null) ?: continue
                val title = obj.optString("title", null)
                    ?: "Chapter ${i + 1}"
                val date = obj.optString("created_at", null)
                val chapterUrl = "$mainUrl/$slug/$chapterId"
                chapters.add(Chapter(name = title, url = chapterUrl, dateOfRelease = date))
            }
            chapters
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val contentElement = document.selectFirstOrNull("div.chapter-content, div#chapter-content, div.content")
            ?: return null
        contentElement.select("script, style, .ads, .adsbygoogle, [class*='ads'], [id*='ads'], .chapter-end-notice").remove()
        return contentElement.html().trim().takeIf { it.isNotBlank() }
    }
}
