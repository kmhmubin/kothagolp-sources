package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PawReadProvider : MainProvider() {

    override val name = "PawRead"
    override val mainUrl = "https://m.pawread.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=pawread.com&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Sort", "sort"),
        FilterOption("New", "new"),
        FilterOption("Update", "update")
    )

    override val tags = listOf(
        FilterOption("All Genres", "0"),
        FilterOption("Fantasy", "1"),
        FilterOption("Eastern Fantasy", "2"),
        FilterOption("Romance", "3"),
        FilterOption("Action", "4"),
        FilterOption("Sci-fi", "5"),
        FilterOption("Horror", "6"),
        FilterOption("Mystery", "7"),
        FilterOption("History", "8"),
        FilterOption("Sports", "9"),
        FilterOption("Others", "10")
    )

    private fun parseCoverUrl(element: Element): String? {
        val img = element.selectFirstOrNull("dd > a > img") ?: return null
        return img.attrOrNull("data-original")?.takeIf { it.isNotBlank() }
            ?: img.attrOrNull("src")?.takeIf { it.isNotBlank() }
    }

    private fun parseNovelListElement(element: Element): Novel? {
        val titleLink = element.selectFirstOrNull("dt > a") ?: return null
        val name = titleLink.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = titleLink.attrOrNull("href") ?: return null
        val novelUrl = fixUrl(href) ?: return null
        val posterUrl = parseCoverUrl(element)
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    private fun parseNovelList(document: Document): List<Novel> {
        return document.select("div#BookList > dl.bc_w").mapNotNull { parseNovelListElement(it) }
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val genre = tag?.takeUnless { it.isNullOrEmpty() } ?: "0"
        val sort = orderBy?.takeUnless { it.isNullOrEmpty() } ?: "sort"
        val url = "$mainUrl/list/$genre-0-0/$sort?page=$page"
        val document = get(url).document
        val novels = parseNovelList(document)
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?searchkey=$encodedQuery"
        val document = get(url).document
        return document.select("dl.bc_w").mapNotNull { parseNovelListElement(it) }
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val name = document.selectFirstOrNull("div.detailwrap > h1")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("div#BookIntroduceId h1")?.textOrNull()?.trim()
            ?: return null
        val posterUrl = document.selectFirstOrNull("div#Cover > img")?.attrOrNull("src")
            ?.let { fixUrl(it) }
        val synopsis = document.select("div#novelIntro > p").joinToString("\n\n") { it.text() }
            .trim().takeIf { it.isNotBlank() }
        val author = extractLabelValue(document, "Author")
        val status = extractLabelValue(document, "Status")
        val tags = document.select("div.tag_wrap > a.tag_btn").mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotBlank() }
        val chapters = parseChapters(document)
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private fun extractLabelValue(document: Document, label: String): String? {
        return document.select("p.txtItme").firstOrNull { element ->
            element.textOrNull()?.contains(label, ignoreCase = true) == true
        }?.textOrNull()?.substringAfter(":")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseChapters(document: Document): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        for (item in document.select("div.item-box[onclick]")) {
            val onclick = item.attrOrNull("onclick") ?: continue
            val urlMatch = Regex("location\\.href='([^']+)'").find(onclick)
            val chapterPath = urlMatch?.groupValues?.getOrNull(1)
                ?: item.selectFirstOrNull("a[href]")?.attrOrNull("href")
                ?: continue
            val chapterUrl = fixUrl(chapterPath) ?: continue
            val chapterName = item.selectFirstOrNull("div.title")?.textOrNull()?.trim()
                ?: "Chapter ${chapters.size + 1}"
            val dateOfRelease = item.selectFirstOrNull("div.date")?.textOrNull()?.trim()
            chapters.add(Chapter(name = chapterName, url = chapterUrl, dateOfRelease = dateOfRelease))
        }
        return chapters
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val contentElement = document.selectFirstOrNull("div.main")
            ?: document.selectFirstOrNull("div#novelArticle")
            ?: return null
        contentElement.select(".adsbygoogle, script, style, iframe").remove()
        return contentElement.html()
    }
}
