package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider

class PawReadProvider : MainProvider() {

    override val name = "PawRead"
    override val mainUrl = "https://pawread.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=pawread.com&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Time Updated", "update"),
        FilterOption("Time Posted", "post"),
        FilterOption("Most Clicked", "click")
    )

    override val tags = listOf(
        FilterOption("All", "All"),
        FilterOption("Fantasy", "Fantasy"),
        FilterOption("Action", "Action"),
        FilterOption("Xuanhuan", "Xuanhuan"),
        FilterOption("Romance", "Romance"),
        FilterOption("Adventure", "Adventure"),
        FilterOption("Sci-fi", "Sci-fi"),
        FilterOption("Horror", "Horror"),
        FilterOption("Mystery", "Mystery"),
        FilterOption("Comedy", "Comedy"),
        FilterOption("School Life", "School+Life"),
        FilterOption("Martial Arts", "Martial+Arts"),
        FilterOption("History", "History"),
        FilterOption("Drama", "Drama")
    )

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val sort = orderBy?.takeIf { it.isNotBlank() } ?: "click"
        val genre = tag?.takeIf { it.isNotBlank() && it != "All" } ?: ""
        val genrePath = if (genre.isNotBlank()) genre else "All"
        val url = "$mainUrl/list/all-$genrePath/$sort/?page=$page"
        val document = get(url).document

        val novels = document.select(".list-comic-thumbnail").mapNotNull { card ->
            val anchor = card.selectFirstOrNull(".caption > h3 > a") ?: return@mapNotNull null
            val title = anchor.textOrNull()?.trim() ?: return@mapNotNull null
            val href = anchor.attrOrNull("href") ?: return@mapNotNull null
            val novelUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            val cover = card.selectFirstOrNull(".image-link > img")?.attrOrNull("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            Novel(name = title, url = novelUrl, posterUrl = cover, apiName = this.name)
        }

        val hasNext = document.selectFirstOrNull(".pagination .next, a[rel=next]") != null
            || document.select(".list-comic-thumbnail").size >= 20

        return MainPageResult(url = url, novels = novels, hasNextPage = hasNext)
    }

    override suspend fun search(query: String): List<Novel> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/search/?keywords=$encoded"
        return try {
            val document = get(url).document
            document.select(".list-comic-thumbnail").mapNotNull { card ->
                val anchor = card.selectFirstOrNull(".caption > h3 > a") ?: return@mapNotNull null
                val title = anchor.textOrNull()?.trim() ?: return@mapNotNull null
                val href = anchor.attrOrNull("href") ?: return@mapNotNull null
                val novelUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val cover = card.selectFirstOrNull(".image-link > img")?.attrOrNull("src")
                    ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                Novel(name = title, url = novelUrl, posterUrl = cover, apiName = this.name)
            }
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document

        val title = document.selectFirstOrNull("h1.j_novel_title, .book-title h1, h1")
            ?.textOrNull()?.trim() ?: return null

        val coverStyle = document.selectFirstOrNull("#Cover, .book-cover, .novel-cover")
            ?.attrOrNull("style")
        val coverImg = document.selectFirstOrNull("#Cover img, .book-cover img, .novel-cover img")
            ?.attrOrNull("src")
        val cover = coverImg?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            ?: coverStyle?.let { style ->
                Regex("""url\(['"]?([^'")\s]+)['"]?\)""").find(style)?.groupValues?.get(1)
                    ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            }

        val infoItems = document.select("p.txtItme, .book-info p, .novel-info p")
        var author: String? = null
        var status: String? = null
        for (item in infoItems) {
            val text = item.textOrNull() ?: continue
            when {
                text.contains("Author", ignoreCase = true) ->
                    author = text.substringAfter(":").trim().takeIf { it.isNotBlank() }
                text.contains("Status", ignoreCase = true) ->
                    status = text.substringAfter(":").trim().takeIf { it.isNotBlank() }
            }
        }

        val synopsis = document.selectFirstOrNull("#full-des, .book-desc, .novel-desc, .intro")
            ?.textOrNull()?.trim()

        val tags = document.select("a.btn-default, .genre-tags a, .tags a")
            .mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotBlank() }

        val chapters = loadChapters(fullUrl)

        return NovelDetails(
            url = fullUrl, name = title, chapters = chapters,
            author = author, posterUrl = cover, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private suspend fun loadChapters(novelUrl: String): List<Chapter> {
        return try {
            val document = get(novelUrl).document
            val novelPath = novelUrl.trimEnd('/')
            document.select(".item-box").mapNotNull { item ->
                val onclick = item.attrOrNull("onclick") ?: ""
                val chapterId = Regex("""'(\d+)'""").find(onclick)?.groupValues?.get(1)
                    ?: Regex("""\b(\d+)\b""").find(onclick)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val chapterUrl = "$novelPath/$chapterId.html"
                val name = item.selectFirstOrNull(".chapter-name, .title, a, span")
                    ?.textOrNull()?.trim()
                    ?: "Chapter $chapterId"
                val date = item.selectFirstOrNull(".date, .time, .update-time")?.textOrNull()?.trim()
                Chapter(name = name, url = chapterUrl, dateOfRelease = date)
            }
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val content = document.selectFirstOrNull("#chapter_item, .chapter-item, .chapter-content, div.main")
            ?: return null
        content.select("script, style, .ads, .adsbygoogle, [class*='ad-'], [id*='ad-']").remove()
        val html = content.html().trim()
        return html.takeIf { it.isNotBlank() }
    }
}
