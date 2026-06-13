package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterGroup
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class EmpireNovelProvider : MainProvider() {

    override val name = "EmpireNovel"
    override val mainUrl = "https://www.empirenovel.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=empirenovel.com&sz=64"
    override val hasMainPage = true
    override val rateLimitTime: Long = 750L

    override val tags = listOf(
        FilterOption("All", ""),
        FilterOption("Action", "action"),
        FilterOption("Adult", "adult"),
        FilterOption("Adventure", "adventure"),
        FilterOption("Comedy", "comedy"),
        FilterOption("Drama", "drama"),
        FilterOption("Eastern", "eastern"),
        FilterOption("Ecchi", "ecchi"),
        FilterOption("Fantasy", "fantasy"),
        FilterOption("Game", "game"),
        FilterOption("Gender Bender", "gender-bender"),
        FilterOption("Harem", "harem"),
        FilterOption("Historical", "historical"),
        FilterOption("Horror", "horror"),
        FilterOption("Isekai", "isekai"),
        FilterOption("Josei", "josei"),
        FilterOption("Korean", "korean"),
        FilterOption("Martial Arts", "martial-arts"),
        FilterOption("Mature", "mature"),
        FilterOption("Mecha", "mecha"),
        FilterOption("Mystery", "mystery"),
        FilterOption("Psychological", "psychological"),
        FilterOption("Reincarnation", "reincarnation"),
        FilterOption("Romance", "romance"),
        FilterOption("School Life", "school-life"),
        FilterOption("Sci-fi", "sci-fi"),
        FilterOption("Seinen", "seinen"),
        FilterOption("Shoujo", "shoujo"),
        FilterOption("Shounen", "shounen"),
        FilterOption("Slice of Life", "slice-of-life"),
        FilterOption("Smut", "smut"),
        FilterOption("Sports", "sports"),
        FilterOption("Supernatural", "supernatural"),
        FilterOption("System", "system"),
        FilterOption("Tragedy", "tragedy"),
        FilterOption("Wuxia", "wuxia"),
        FilterOption("Xianxia", "xianxia"),
        FilterOption("Xuanhuan", "xuanhuan"),
        FilterOption("Yaoi", "yaoi"),
        FilterOption("Yuri", "yuri")
    )

    override val orderBys = listOf(
        FilterOption("Latest Updates", "updated_at"),
        FilterOption("Name (A-Z)", "name")
    )

    override val extraFilterGroups = listOf(
        FilterGroup(
            key = "status", label = "Status",
            options = listOf(
                FilterOption("All", ""),
                FilterOption("Ongoing", "1"),
                FilterOption("Completed", "2"),
                FilterOption("Abandoned", "3")
            ),
            defaultValue = ""
        )
    )

    private fun extractImageUrl(imgElement: Element?): String? {
        if (imgElement == null) return null
        val dataSrc = imgElement.attrOrNull("data-src")?.takeIf { it.isNotBlank() }
        val src = imgElement.attrOrNull("src")?.takeIf { it.isNotBlank() }
        val rawUrl = dataSrc ?: src ?: return null
        return if (rawUrl.startsWith("http")) rawUrl
        else "$mainUrl/${rawUrl.removePrefix("/")}".replace("//", "/").replace(":/", "://")
    }

    private fun parseStatus(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        return when (statusText.lowercase().trim()) {
            "ongoing" -> "Ongoing"
            "completed" -> "Completed"
            "abandoned" -> "Abandoned"
            else -> statusText.trim().replaceFirstChar { it.uppercase() }
        }
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val params = mutableListOf<String>()
        if (!tag.isNullOrEmpty()) params.add("category=$tag")
        val sortBy = orderBy.takeUnless { it.isNullOrEmpty() } ?: "updated_at"
        params.add("sort_by=$sortBy")
        val sortDir = if (sortBy == "name") "asc" else "desc"
        params.add("sort_dir=$sortDir")
        val status = extraFilters["status"]
        if (!status.isNullOrEmpty()) params.add("status=$status")
        params.add("page=$page")
        val url = "$mainUrl/novels-list?${params.joinToString("&")}"
        val document = get(url).document
        val novels = document.select(".novellist_item").mapNotNull { parseNovelFromList(it) }
        return MainPageResult(url = url, novels = novels)
    }

    private fun parseNovelFromList(element: Element): Novel? {
        val titleElement = element.selectFirstOrNull("h2.fs-6 a") ?: return null
        val name = titleElement.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = titleElement.attrOrNull("href") ?: return null
        val novelUrl = fixUrl(href) ?: return null
        val imgElement = element.selectFirstOrNull("img.rounded-3")
        val posterUrl = extractImageUrl(imgElement)
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search-live?q=$encodedQuery"
        val response = get(url, mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"))
        val jsonArray = JSONArray(response.text)
        val novels = mutableListOf<Novel>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val name = obj.optString("name", null)?.takeIf { it.isNotBlank() } ?: continue
            val slug = obj.optString("slug", null)?.takeIf { it.isNotBlank() } ?: continue
            val novelUrl = fixUrl("/novel/$slug") ?: continue
            val posterUrl = "$mainUrl/uploads/novel/$slug/cover/cover_thumb.jpg"
            novels.add(Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name))
        }
        return novels
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val name = document.selectFirstOrNull("h1[itemprop='name']")?.textOrNull()?.trim() ?: return null
        val metadata = extractMetadata(document)
        val chapters = generateChaptersInstantly(document, url)
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = metadata.author, posterUrl = metadata.posterUrl,
            synopsis = metadata.synopsis, tags = metadata.tags.ifEmpty { null },
            status = metadata.status
        )
    }

    private data class NovelMetadata(
        val author: String? = null, val posterUrl: String? = null,
        val synopsis: String? = null, val tags: List<String> = emptyList(),
        val status: String? = null
    )

    private fun extractMetadata(document: Document): NovelMetadata {
        val author = document.selectFirstOrNull("span[itemprop='author']")?.textOrNull()?.trim()
        val posterImg = document.selectFirstOrNull("img[itemprop='image']")
        val posterUrl = extractImageUrl(posterImg)
        val tags = document.select("a[itemprop='genre']")
            .mapNotNull { it.textOrNull()?.trim() }.filter { it.isNotBlank() }.distinct()
        var status: String? = null
        document.select("div.d-flex.justify-content-between").forEach { div ->
            val text = div.ownText().trim()
            if (text.startsWith("Status", ignoreCase = true)) {
                status = parseStatus(div.selectFirstOrNull("span")?.textOrNull())
            }
        }
        val synopsisElement = document.selectFirstOrNull("dd[itemprop='description']")
        synopsisElement?.select("#dots, #read_more, #more")?.remove()
        val synopsis = synopsisElement?.text()?.trim()?.takeIf { it.isNotBlank() }
        return NovelMetadata(author, posterUrl, synopsis, tags, status)
    }

    private fun generateChaptersInstantly(document: Document, novelUrl: String): List<Chapter> {
        try {
            val firstChapterLink = document.select("a").find {
                it.text().contains("First Chapter", ignoreCase = true)
            }
            val firstChapterUrl = firstChapterLink?.attrOrNull("href") ?: return emptyList()
            val lastChapterLink = document.select("a").find {
                it.text().contains("Last Chapter", ignoreCase = true)
            }
            val lastChapterUrl = lastChapterLink?.attrOrNull("href") ?: return emptyList()
            val firstParts = firstChapterUrl.split("/")
            val lastParts = lastChapterUrl.split("/")
            val firstNumber = firstParts.lastOrNull()?.toIntOrNull() ?: return emptyList()
            val lastNumber = lastParts.lastOrNull()?.toIntOrNull() ?: return emptyList()
            if (firstNumber < 0 || lastNumber < firstNumber || lastNumber > 10000) return emptyList()
            val basePath = firstParts.dropLast(1).joinToString("/")
            val chapters = mutableListOf<Chapter>()
            for (chapterNumber in firstNumber..lastNumber) {
                val chapterUrl = fixUrl("$basePath/$chapterNumber") ?: continue
                chapters.add(Chapter(name = "Chapter $chapterNumber", url = chapterUrl))
            }
            return chapters
        } catch (_: Exception) { return emptyList() }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val contentElement = document.selectFirstOrNull("#read-novel") ?: return null
        contentElement.select("div.wrapper").remove()
        contentElement.select("script, style").remove()
        return contentElement.html()
    }
}
