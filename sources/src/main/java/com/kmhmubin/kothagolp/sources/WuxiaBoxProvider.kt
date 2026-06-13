package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider

/**
 * Provider for WuxiaBox.com
 * NOTE: This site uses Cloudflare protection. The host app's NetworkClient
 * will attempt to bypass it automatically using stored CF cookies.
 */
class WuxiaBoxProvider : MainProvider() {

    override val name = "WuxiaBox"
    override val mainUrl = "https://www.wuxiabox.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=wuxiabox.com&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("New", "newstime"),
        FilterOption("Popular", "onclick"),
        FilterOption("Updates", "lastdotime")
    )

    override val tags = listOf(
        FilterOption("All", "all"),
        FilterOption("Action", "action"),
        FilterOption("Adventure", "adventure"),
        FilterOption("Comedy", "comedy"),
        FilterOption("Contemporary Romance", "contemporary-romance"),
        FilterOption("Drama", "drama"),
        FilterOption("Eastern Fantasy", "eastern-fantasy"),
        FilterOption("Fan-Fic", "fan-fiction"),
        FilterOption("Fantasy", "fantasy"),
        FilterOption("Fantasy Romance", "fantasy-romance"),
        FilterOption("Gender Bender", "gender-bender"),
        FilterOption("Harem", "harem"),
        FilterOption("Historical", "historical"),
        FilterOption("Horror", "horror"),
        FilterOption("Isekai", "isekai"),
        FilterOption("Josei", "josei"),
        FilterOption("Magic", "magic"),
        FilterOption("Martial Arts", "martial-arts"),
        FilterOption("Mecha", "mecha"),
        FilterOption("Military", "military"),
        FilterOption("Mystery", "mystery"),
        FilterOption("Psychological", "psychological"),
        FilterOption("Romance", "romance"),
        FilterOption("School Life", "school-life"),
        FilterOption("Sci-fi", "sci-fi"),
        FilterOption("Seinen", "seinen"),
        FilterOption("Shoujo", "shoujo"),
        FilterOption("Shounen", "shounen"),
        FilterOption("Slice of Life", "slice-of-life"),
        FilterOption("Sports", "sports"),
        FilterOption("Supernatural", "supernatural"),
        FilterOption("Tragedy", "tragedy"),
        FilterOption("Wuxia", "wuxia"),
        FilterOption("Xianxia", "xianxia"),
        FilterOption("Xuanhuan", "xuanhuan"),
        FilterOption("Yaoi", "yaoi"),
        FilterOption("Yuri", "yuri")
    )

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val tagSlug = tag?.takeUnless { it.isNullOrBlank() } ?: "all"
        val sortBy = orderBy?.takeUnless { it.isNullOrEmpty() } ?: "onclick"
        val url = "$mainUrl/list/$tagSlug/all-$sortBy-$page.html"
        val document = get(url).document
        val novels = document.select("li.novel-item").mapNotNull { select ->
            val a = select.selectFirstOrNull("a[title]") ?: return@mapNotNull null
            val title = a.attrOrNull("title")?.takeIf { it.trim().isNotEmpty() }
                ?: a.selectFirstOrNull("h4.novel-title")?.textOrNull()
                ?: return@mapNotNull null
            val href = a.attrOrNull("href") ?: return@mapNotNull null
            val imgElement = select.selectFirstOrNull("img")
            val posterUrl = imgElement?.attrOrNull("data-src")?.ifBlank { null }
                ?: imgElement?.attrOrNull("src")
            Novel(name = title, url = fixUrl(href) ?: return@mapNotNull null, posterUrl = posterUrl?.let { fixUrl(it) }, apiName = this.name)
        }
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val response = post(
            "$mainUrl/e/search/index.php",
            data = mapOf("show" to "title", "tempid" to "1", "tbname" to "news", "keyboard" to encodedQuery)
        )
        // Server redirects to result/?searchid=XXXXX
        val redirectUrl = response.headers["location"] ?: response.headers["Location"]
            ?: response.document.selectFirstOrNull("meta[http-equiv=refresh]")
                ?.attrOrNull("content")?.substringAfter("url=")
            ?: ""
        val searchId = Regex("searchid=(\\d+)").find(redirectUrl + response.text)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val results = mutableListOf<Novel>()
        var currentPage = 0
        while (true) {
            val url = "$mainUrl/e/search/result/index.php?page=$currentPage&searchid=$searchId"
            val document = get(url).document
            val pageResults = document.select("li.novel-item").mapNotNull { element ->
                val node = element.selectFirstOrNull("a[title]") ?: return@mapNotNull null
                val href = fixUrl(node.attrOrNull("href") ?: return@mapNotNull null) ?: return@mapNotNull null
                val title = node.attrOrNull("title")?.takeIf { it.isNotBlank() }
                    ?: element.selectFirstOrNull("h4.novel-title")?.textOrNull()
                    ?: return@mapNotNull null
                val imgElement = element.selectFirstOrNull("img")
                val cover = imgElement?.attrOrNull("data-src")?.ifBlank { null } ?: imgElement?.attrOrNull("src")
                Novel(name = title, url = href, posterUrl = cover?.let { fixUrl(it) }, apiName = this.name)
            }
            if (pageResults.isEmpty()) break
            results.addAll(pageResults)
            currentPage++
            if (currentPage > 10) break // safety limit
        }
        return results
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val title = document.selectFirstOrNull("h1.novel-title")?.textOrNull() ?: return null
        val author = document.selectFirstOrNull("div.author [itemprop=author]")?.textOrNull()
        val synopsis = document.selectFirstOrNull("meta[itemprop=description]")?.attrOrNull("content")
            ?: document.selectFirstOrNull("div.summary, div.desc, #intro")?.textOrNull()
            ?: ""
        val cover = document.selectFirstOrNull("div.fixed-img img")?.let { img ->
            val src = img.attrOrNull("data-src")?.ifBlank { null } ?: img.attrOrNull("src")
            src?.let { fixUrl(it) }
        }
        val statusText = document.selectFirstOrNull("div.header-stats strong")?.let { el ->
            val text = el.textOrNull()?.trim()
            if (text?.contains("Ongoing", ignoreCase = true) == true || text?.contains("Completed", ignoreCase = true) == true) text else null
        }
        val status = parseStatus(statusText)
        val bookId = fullUrl.substringAfterLast("/").substringBefore(".html")
        val chapters = mutableListOf<Chapter>()
        var currentPage = 0
        while (true) {
            val chaptersPageUrl = "$mainUrl/e/extend/fy.php?page=$currentPage&wjm=$bookId&X-Requested-With=XMLHttpRequest&_=${System.currentTimeMillis()}"
            val chapterDoc = get(chaptersPageUrl).document
            val chapterElements = chapterDoc.select("ul.chapter-list li")
            if (chapterElements.isEmpty()) break
            var pageCount = 0
            val pageChapters = chapterElements.mapNotNull { li ->
                val link = li.selectFirstOrNull("a") ?: return@mapNotNull null
                val chapterUrl = fixUrl(link.attrOrNull("href") ?: return@mapNotNull null) ?: return@mapNotNull null
                val chapterTitle = link.selectFirstOrNull("strong.chapter-title")?.textOrNull()?.trim()
                    ?: "Chapter ${chapters.size + pageCount + 1}"
                pageCount++
                Chapter(name = chapterTitle, url = chapterUrl)
            }
            chapters.addAll(pageChapters)
            currentPage++
            if (pageChapters.isEmpty()) break
        }
        return NovelDetails(
            url = fullUrl, name = title, chapters = chapters,
            author = author, posterUrl = cover, synopsis = synopsis,
            status = status
        )
    }

    private fun parseStatus(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        return when {
            statusText.contains("Ongoing", ignoreCase = true) -> "Ongoing"
            statusText.contains("Completed", ignoreCase = true) -> "Completed"
            else -> null
        }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val contentElement = document.selectFirstOrNull("div.chapter-content") ?: return null
        contentElement.select("img[src*=disable-blocker.jpg], script, div[align=center]").remove()
        return contentElement.html()
    }
}
