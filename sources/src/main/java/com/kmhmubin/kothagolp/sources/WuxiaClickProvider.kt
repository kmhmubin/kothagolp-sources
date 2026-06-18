package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.json.JSONArray
import org.json.JSONObject

class WuxiaClickProvider : MainProvider() {

    override val name = "WuxiaClick"
    override val mainUrl = "https://wuxia.click"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=wuxia.click&sz=64"
    override val hasMainPage = true

    private val apiUrl = "https://wuxiaworld.eu/api"

    override val orderBys = listOf(
        FilterOption("Rating", "-rating"),
        FilterOption("Most Views", "-weekly_views"),
        FilterOption("Latest", "-created")
    )

    override val tags = listOf(
        FilterOption("Action", "action"),
        FilterOption("Adventure", "adventure"),
        FilterOption("Comedy", "comedy"),
        FilterOption("Drama", "drama"),
        FilterOption("Fantasy", "fantasy"),
        FilterOption("Harem", "harem"),
        FilterOption("Historical", "historical"),
        FilterOption("Horror", "horror"),
        FilterOption("Josei", "josei"),
        FilterOption("Martial Arts", "martial-arts"),
        FilterOption("Mature", "mature"),
        FilterOption("Mecha", "mecha"),
        FilterOption("Mystery", "mystery"),
        FilterOption("Psychological", "psychological"),
        FilterOption("Romance", "romance"),
        FilterOption("School Life", "school-life"),
        FilterOption("Sci-fi", "sci-fi"),
        FilterOption("Seinen", "seinen"),
        FilterOption("Shoujo", "shoujo"),
        FilterOption("Shounen", "shounen"),
        FilterOption("Slice of Life", "slice-of-life"),
        FilterOption("Supernatural", "supernatural"),
        FilterOption("Tragedy", "tragedy"),
        FilterOption("Wuxia", "wuxia"),
        FilterOption("Xianxia", "xianxia"),
        FilterOption("Xuanhuan", "xuanhuan")
    )

    private fun parseStatus(code: String?): String? {
        return when (code?.uppercase()) {
            "OG" -> "Ongoing"
            "CP" -> "Completed"
            else -> null
        }
    }

    private fun parseNovelFromJson(obj: JSONObject): Novel? {
        val name = obj.optString("name", null)?.takeIf { it.isNotBlank() } ?: return null
        val slug = obj.optString("slug", null)?.takeIf { it.isNotBlank() } ?: return null
        val novelUrl = "$mainUrl/novel/$slug"
        val image = obj.optString("image", null)
        val posterUrl = if (!image.isNullOrBlank() && image.startsWith("http")) image else null
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val offset = (page - 1) * 12
        val order = orderBy.takeUnless { it.isNullOrEmpty() } ?: "-rating"
        val url = "$apiUrl/search/?search=&offset=$offset&limit=12&order=$order"
        val response = get(url)
        return try {
            val json = JSONObject(response.text)
            val results = json.optJSONArray("results") ?: JSONArray()
            val list = mutableListOf<Novel>()
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                parseNovelFromJson(obj)?.let { list.add(it) }
            }
            val hasNext = !json.optString("next", null).isNullOrBlank()
            MainPageResult(url = url, novels = list, hasNextPage = hasNext)
        } catch (_: Exception) { MainPageResult(url = url, novels = emptyList()) }
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/search/?search=$encodedQuery&offset=0&limit=12&order=-weekly_views"
        val response = get(url)
        return try {
            val json = JSONObject(response.text)
            val results = json.optJSONArray("results") ?: JSONArray()
            val list = mutableListOf<Novel>()
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                parseNovelFromJson(obj)?.let { list.add(it) }
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun load(url: String): NovelDetails? {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val detailUrl = "$apiUrl/novels/$slug/"
        val response = get(detailUrl)
        return try {
            val obj = JSONObject(response.text)
            val name = obj.optString("name", null)?.takeIf { it.isNotBlank() } ?: return null
            val description = obj.optString("description", null)?.takeIf { it.isNotBlank() }
            val image = obj.optString("image", null)
            val posterUrl = if (!image.isNullOrBlank() && image.startsWith("http")) image else null
            val author = obj.optJSONObject("author")?.optString("name", null)?.takeIf { it.isNotBlank() }
            val status = parseStatus(obj.optString("status", null))
            val categoriesArr = obj.optJSONArray("categories") ?: JSONArray()
            val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
            val genres = mutableListOf<String>()
            for (i in 0 until categoriesArr.length()) {
                categoriesArr.optJSONObject(i)?.optString("name", null)
                    ?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
            }
            for (i in 0 until tagsArr.length()) {
                tagsArr.optJSONObject(i)?.optString("name", null)
                    ?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
            }
            val chapters = loadChapterList(slug)
            NovelDetails(
                url = url, name = name, chapters = chapters,
                author = author, posterUrl = posterUrl, synopsis = description,
                tags = genres.ifEmpty { null }, status = status
            )
        } catch (_: Exception) { null }
    }

    private suspend fun loadChapterList(slug: String): List<Chapter> {
        return try {
            val chaptersUrl = "$apiUrl/chapters/$slug/"
            val response = get(chaptersUrl)
            val arr = JSONArray(response.text)
            val chapters = mutableListOf<Chapter>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val novSlugChapSlug = obj.optString("novSlugChapSlug", null)?.takeIf { it.isNotBlank() } ?: continue
                val title = obj.optString("title", null)?.takeIf { it.isNotBlank() }
                    ?: "Chapter ${obj.optInt("index", i + 1)}"
                val chapterUrl = "$mainUrl/chapter/$novSlugChapSlug"
                val date = obj.optString("timeAdded", null)?.takeIf { it.isNotBlank() }
                chapters.add(Chapter(name = title, url = chapterUrl, dateOfRelease = date))
            }
            chapters.reversed()
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val novSlugChapSlug = url.trimEnd('/').substringAfterLast("/chapter/")
            .takeIf { it.isNotBlank() } ?: return null
        val contentUrl = "$apiUrl/getchapter/$novSlugChapSlug/"
        return try {
            val response = get(contentUrl)
            val obj = JSONObject(response.text)
            val title = obj.optString("title", null) ?: ""
            val text = obj.optString("text", null) ?: return null
            val paragraphs = text.split("\n")
                .filter { it.isNotBlank() }
                .joinToString("\n") { "<p>${it.trim()}</p>" }
            "<h2>${title}</h2>\n$paragraphs"
        } catch (_: Exception) { null }
    }
}
