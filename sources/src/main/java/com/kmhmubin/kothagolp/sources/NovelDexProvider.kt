package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.json.JSONArray
import org.json.JSONObject

class NovelDexProvider : MainProvider() {

    override val name = "NovelDex"
    override val mainUrl = "https://noveldex.io"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=noveldex.io&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Popular", "popular"),
        FilterOption("Latest", "latest"),
        FilterOption("Rating", "rating"),
        FilterOption("New", "new")
    )

    override val tags = listOf(
        FilterOption("Action", "action"),
        FilterOption("Adventure", "adventure"),
        FilterOption("Comedy", "comedy"),
        FilterOption("Drama", "drama"),
        FilterOption("Fantasy", "fantasy"),
        FilterOption("Horror", "horror"),
        FilterOption("Isekai", "isekai"),
        FilterOption("Martial Arts", "martial-arts"),
        FilterOption("Mystery", "mystery"),
        FilterOption("Romance", "romance"),
        FilterOption("Sci-fi", "sci-fi"),
        FilterOption("Slice of Life", "slice-of-life"),
        FilterOption("Supernatural", "supernatural"),
        FilterOption("Tragedy", "tragedy"),
        FilterOption("Wuxia", "wuxia"),
        FilterOption("Xianxia", "xianxia")
    )

    private fun parseStatus(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        return when (statusText.uppercase()) {
            "ONGOING" -> "Ongoing"
            "COMPLETED" -> "Completed"
            "HIATUS" -> "On Hiatus"
            "DROPPED" -> "Dropped"
            else -> null
        }
    }

    private fun buildCoverUrl(coverImage: String?): String? {
        if (coverImage.isNullOrBlank()) return null
        return if (coverImage.startsWith("http")) coverImage else "$mainUrl$coverImage"
    }

    private fun buildNovelUrl(obj: JSONObject): String? {
        val slug = obj.optString("slug", null)?.takeIf { it.isNotBlank() } ?: return null
        val type = obj.optString("type", "WEB_NOVEL")
        return "$mainUrl/series/novel/$slug"
    }

    private fun parseNovelFromJson(obj: JSONObject): Novel? {
        val name = obj.optString("title", null)?.takeIf { it.isNotBlank() } ?: return null
        val novelUrl = buildNovelUrl(obj) ?: return null
        val coverImage = obj.optString("coverImage", null)
        val posterUrl = buildCoverUrl(coverImage)
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val sort = orderBy.takeUnless { it.isNullOrEmpty() } ?: "popular"
        val url = "$mainUrl/api/series?page=$page&limit=24&sort=$sort"
        val response = get(url)
        return try {
            val json = JSONObject(response.text)
            val data = json.optJSONArray("data") ?: JSONArray()
            val novels = mutableListOf<Novel>()
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                parseNovelFromJson(obj)?.let { novels.add(it) }
            }
            val meta = json.optJSONObject("meta")
            val hasNextPage = meta?.optBoolean("hasMore", true) ?: true
            MainPageResult(url = url, novels = novels, hasNextPage = hasNextPage)
        } catch (_: Exception) {
            MainPageResult(url = url, novels = emptyList(), hasNextPage = false)
        }
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/series?page=1&limit=24&search=$encodedQuery"
        val response = get(url)
        return try {
            val json = JSONObject(response.text)
            val data = json.optJSONArray("data") ?: JSONArray()
            val novels = mutableListOf<Novel>()
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                parseNovelFromJson(obj)?.let { novels.add(it) }
            }
            novels
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val slug = fullUrl.trimEnd('/').substringAfterLast("/")
        val response = get(fullUrl)
        val pageText = response.text
        return try {
            val nextDataMatch = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(pageText)
            if (nextDataMatch != null) {
                parseFromNextData(fullUrl, slug, nextDataMatch.groupValues[1])
            } else {
                parseFromRsc(fullUrl, slug, pageText)
            }
        } catch (_: Exception) { null }
    }

    private fun parseFromNextData(url: String, slug: String, jsonText: String): NovelDetails? {
        return try {
            val root = JSONObject(jsonText)
            val pageProps = root.optJSONObject("props")?.optJSONObject("pageProps") ?: return null
            val seriesObj = pageProps.optJSONObject("series") ?: pageProps.optJSONObject("novel") ?: return null
            val name = seriesObj.optString("title", null)?.takeIf { it.isNotBlank() } ?: return null
            val description = seriesObj.optString("description", null)?.takeIf { it.isNotBlank() }
            val coverImage = seriesObj.optString("coverImage", null)
            val posterUrl = buildCoverUrl(coverImage)
            val authorObj = seriesObj.optJSONObject("author")
            val author = authorObj?.optString("name", null)?.takeIf { it.isNotBlank() }
                ?: seriesObj.optString("author", null)?.takeIf { it.isNotBlank() }
            val status = parseStatus(seriesObj.optString("status", null))
            val genresArr = seriesObj.optJSONArray("genres") ?: seriesObj.optJSONArray("categories") ?: JSONArray()
            val genres = mutableListOf<String>()
            for (i in 0 until genresArr.length()) {
                val g = genresArr.optJSONObject(i)?.optString("name", null)
                    ?: genresArr.optString(i, null)
                g?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
            }
            val chaptersArr = pageProps.optJSONArray("allChapters")
                ?: pageProps.optJSONArray("chapters")
                ?: seriesObj.optJSONArray("chapters")
                ?: JSONArray()
            val chapters = parseChaptersFromJsonArray(chaptersArr, slug)
            NovelDetails(
                url = url, name = name, chapters = chapters,
                author = author, posterUrl = posterUrl, synopsis = description,
                tags = genres.ifEmpty { null }, status = status
            )
        } catch (_: Exception) { null }
    }

    private fun parseFromRsc(url: String, slug: String, pageText: String): NovelDetails? {
        return try {
            val titleMatch = Regex(""""title"\s*:\s*"([^"]+)"""").find(pageText)
            val name = titleMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            val descMatch = Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(pageText)
            val description = descMatch?.groupValues?.getOrNull(1)
                ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.takeIf { it.isNotBlank() }
            val coverMatch = Regex(""""coverImage"\s*:\s*"([^"]+)"""").find(pageText)
            val posterUrl = coverMatch?.groupValues?.getOrNull(1)?.let { buildCoverUrl(it) }
            val authorMatch = Regex(""""author"\s*:\s*\{[^}]*"name"\s*:\s*"([^"]+)"""").find(pageText)
            val author = authorMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            val statusMatch = Regex(""""status"\s*:\s*"([^"]+)"""").find(pageText)
            val status = parseStatus(statusMatch?.groupValues?.getOrNull(1))
            val chaptersMatch = Regex(""""(?:allChapters|chapters)"\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
                .find(pageText)
            val chapters = if (chaptersMatch != null) {
                try {
                    parseChaptersFromJsonArray(JSONArray(chaptersMatch.groupValues[1]), slug)
                } catch (_: Exception) { emptyList() }
            } else emptyList()
            NovelDetails(
                url = url, name = name, chapters = chapters,
                author = author, posterUrl = posterUrl, synopsis = description,
                status = status
            )
        } catch (_: Exception) { null }
    }

    private fun parseChaptersFromJsonArray(arr: JSONArray, slug: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val number = obj.optInt("number", -1).takeIf { it >= 0 }
                ?: obj.optInt("index", -1).takeIf { it >= 0 }
                ?: continue
            val title = obj.optString("title", null)?.takeIf { it.isNotBlank() }
                ?: "Chapter $number"
            val isLocked = obj.optBoolean("isLocked", false)
            if (isLocked) continue
            val chapterUrl = "$mainUrl/series/novel/$slug/chapter/$number"
            val date = obj.optString("publishedAt", null)?.takeIf { it.isNotBlank() }
            chapters.add(Chapter(name = title, url = chapterUrl, dateOfRelease = date))
        }
        return chapters.sortedBy { it.url.substringAfterLast("/").toIntOrNull() ?: 0 }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val response = get(fullUrl)
        val pageText = response.text
        return try {
            val nextDataMatch = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(pageText)
            if (nextDataMatch != null) {
                extractContentFromNextData(nextDataMatch.groupValues[1])
            } else {
                extractContentFromRsc(pageText)
                    ?: extractContentFromHtml(response.document)
            }
        } catch (_: Exception) {
            extractContentFromHtml(response.document)
        }
    }

    private fun extractContentFromNextData(jsonText: String): String? {
        return try {
            val root = JSONObject(jsonText)
            val pageProps = root.optJSONObject("props")?.optJSONObject("pageProps") ?: return null
            val chapterObj = pageProps.optJSONObject("chapter") ?: return null
            val content = chapterObj.optString("content", null)?.takeIf { it.isNotBlank() } ?: return null
            val title = chapterObj.optString("title", null) ?: ""
            val paragraphs = content.split("\n")
                .filter { it.isNotBlank() }
                .joinToString("\n") { "<p>${it.trim()}</p>" }
            if (title.isNotBlank()) "<h2>$title</h2>\n$paragraphs" else paragraphs
        } catch (_: Exception) { null }
    }

    private fun extractContentFromRsc(pageText: String): String? {
        return try {
            val rscPattern = Regex(""":T([0-9a-fA-F]+),""")
            val match = rscPattern.find(pageText) ?: return null
            val hexSize = match.groupValues[1]
            val size = hexSize.toLong(16).toInt()
            val startIndex = match.range.last + 1
            if (startIndex + size > pageText.length) return null
            val chunk = pageText.substring(startIndex, (startIndex + size).coerceAtMost(pageText.length))
            val contentMatch = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(chunk)
            contentMatch?.groupValues?.getOrNull(1)
                ?.replace("\\n", "\n")?.replace("\\\"", "\"")
                ?.takeIf { it.isNotBlank() }
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n") { "<p>${it.trim()}</p>" }
        } catch (_: Exception) { null }
    }

    private fun extractContentFromHtml(document: org.jsoup.nodes.Document): String? {
        val contentEl = document.selectFirstOrNull("div.reading-content")
            ?: document.selectFirstOrNull("div.prose")
            ?: document.selectFirstOrNull("article.chapter-content")
            ?: return null
        contentEl.select("script, style, .ads, .adsbygoogle").remove()
        val paragraphs = contentEl.select("p")
        if (paragraphs.isNotEmpty()) {
            return paragraphs.joinToString("\n") { "<p>${it.html()}</p>" }
        }
        return contentEl.html().takeIf { it.isNotBlank() }
    }
}
