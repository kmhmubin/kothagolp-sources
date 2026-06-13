package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.json.JSONObject
import org.jsoup.nodes.Document

class LightNovelWorldProvider : MainProvider() {

    override val name = "LightNovelWorld"
    override val mainUrl = "https://lightnovelworld.org"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=lightnovelworld.org&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("New Update", "/updates/"),
        FilterOption("Top Ranked", "/ranking/?sort=rank"),
        FilterOption("Top Reviews", "/ranking/?sort=reviews"),
        FilterOption("Top Collections", "/ranking/?sort=collections")
    )

    override val tags = listOf(
        FilterOption("All", ""),
        FilterOption("Action", "action"),
        FilterOption("Adventure", "adventure"),
        FilterOption("Comedy", "comedy"),
        FilterOption("Drama", "drama"),
        FilterOption("Fantasy", "fantasy"),
        FilterOption("Gender Bender", "gender-bender"),
        FilterOption("Harem", "harem"),
        FilterOption("Historical", "historical"),
        FilterOption("Horror", "horror"),
        FilterOption("Isekai", "isekai"),
        FilterOption("LitRPG", "litrpg"),
        FilterOption("Martial Arts", "martial-arts"),
        FilterOption("Mature", "mature"),
        FilterOption("Mystery", "mystery"),
        FilterOption("Psychological", "psychological"),
        FilterOption("Reincarnation", "reincarnation"),
        FilterOption("Romance", "romance"),
        FilterOption("Sci-fi", "sci-fi"),
        FilterOption("Slice of Life", "slice-of-life"),
        FilterOption("Supernatural", "supernatural"),
        FilterOption("Tragedy", "tragedy"),
        FilterOption("Wuxia", "wuxia"),
        FilterOption("Xianxia", "xianxia")
    )

    private fun withPageParam(url: String, page: Int): String =
        if (url.contains("?")) "$url&page=$page" else "$url?page=$page"

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val basePath = when {
            !tag.isNullOrBlank() -> "/genre/$tag/"
            else -> orderBy?.takeUnless { it.isNullOrEmpty() } ?: "/updates/"
        }
        val url = withPageParam(fixUrl(basePath) ?: "$mainUrl$basePath", page)
        val document = get(url).document

        val cards = (document.select("div.ranking-list > a") + document.select("a.card-link"))
            .distinctBy { it.attrOrNull("href") }

        val novels = cards.mapNotNull { card ->
            val href = card.attrOrNull("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = card.selectFirstOrNull("h4.ranking-item-title, h3.card-title, h4")?.textOrNull()?.trim()
                ?: return@mapNotNull null
            val posterUrl = card.selectFirstOrNull("div.ranking-item-cover > img")?.attrOrNull("src")
                ?: card.selectFirstOrNull("div.card-cover")?.attrOrNull("data-bg-image")
                ?: card.selectFirstOrNull("img")?.attrOrNull("src")
            val novelUrl = fixUrl(href) ?: return@mapNotNull null
            Novel(name = title, url = novelUrl, posterUrl = posterUrl?.let { fixUrl(it) }, apiName = this.name)
        }
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/search/?q=$encodedQuery"
        val responseText = get(url).text
        return try {
            val json = JSONObject(responseText)
            val novelsArray = json.optJSONArray("novels") ?: return emptyList()
            val novels = mutableListOf<Novel>()
            for (i in 0 until novelsArray.length()) {
                val obj = novelsArray.getJSONObject(i)
                val title = obj.optString("title", null)?.trim() ?: continue
                val slug = obj.optString("slug", null)?.trim() ?: continue
                val coverPath = obj.optString("cover_path", null)
                novels.add(Novel(
                    name = title,
                    url = fixUrl("/novel/$slug/") ?: continue,
                    posterUrl = coverPath?.let { fixUrl(it) },
                    apiName = this.name
                ))
            }
            novels
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val title = document.selectFirstOrNull("h1.novel-title")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("meta[property=og:title]")?.attrOrNull("content")?.trim()
            ?: return null
        val author = document.selectFirstOrNull("p.novel-author > a")?.textOrNull()?.trim()
        val posterUrl = document.selectFirstOrNull("meta[property=og:image]")?.attrOrNull("content")?.let { fixUrl(it) }
            ?: document.selectFirstOrNull("img.novel-cover")?.attrOrNull("src")?.let { fixUrl(it) }
        val synopsis = document.selectFirstOrNull("div.summary-content")?.textOrNull()?.trim()
        val tags = document.select("div.genre-tags > span.genre-tag").mapNotNull { it.textOrNull()?.trim() }
        val statusText = document.selectFirstOrNull("span.status-badge")?.textOrNull()?.trim()
        val status = parseStatus(statusText)
        val chapters = getChapters(fullUrl)
        return NovelDetails(
            url = fullUrl, name = title, chapters = chapters, author = author,
            posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private fun parseStatus(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        return when (statusText.lowercase().trim()) {
            "ongoing" -> "Ongoing"
            "completed" -> "Completed"
            "hiatus" -> "On Hiatus"
            else -> statusText.trim().replaceFirstChar { it.uppercase() }
        }
    }

    private suspend fun getChapters(url: String): List<Chapter> {
        val cleanUrl = url.removeSuffix("/")
        val firstPageUrl = "$cleanUrl/chapters/?page=1"
        val document = get(firstPageUrl).document

        val pagination = document.selectFirstOrNull("select#pageSelect")
        if (pagination != null) {
            val lastPageNumber = pagination.select("option").lastOrNull()?.textOrNull()?.toIntOrNull() ?: 1
            val lastPageDoc = get("$cleanUrl/chapters/?page=$lastPageNumber").document
            val totalChapters = lastPageDoc.select("div.chapters-grid > div.chapter-card")
                .lastOrNull()
                ?.selectFirstOrNull("div.chapter-number")
                ?.textOrNull()
                ?.toIntOrNull()
            if (totalChapters != null) {
                return (1..totalChapters).map { num ->
                    Chapter(name = "Chapter $num", url = fixUrl("$cleanUrl/chapter/$num") ?: "$cleanUrl/chapter/$num")
                }
            }
        }

        return document.select("div.chapters-grid > div.chapter-card").mapNotNull { li ->
            val name = li.selectFirstOrNull("h3")?.textOrNull() ?: return@mapNotNull null
            val onClick = li.attrOrNull("onClick") ?: return@mapNotNull null
            val chapterHref = onClick.substringAfter("location.href='", "").substringBefore("'", "")
            if (chapterHref.isBlank()) return@mapNotNull null
            val chapterUrl = fixUrl(chapterHref) ?: return@mapNotNull null
            val dateOfRelease = li.selectFirstOrNull("p.chapter-time")?.textOrNull()
            Chapter(name = name, url = chapterUrl, dateOfRelease = dateOfRelease)
        }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val chapterRoot = document.selectFirstOrNull("div.chapter-text") ?: return null
        chapterRoot.select("script, style, .ads, .ad, .advertisement, .chapter-nav").remove()
        val paragraphs = chapterRoot.select("> p")
        return if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("") { it.outerHtml() }
        } else {
            chapterRoot.html().takeIf { it.isNotBlank() }
        }
    }
}
