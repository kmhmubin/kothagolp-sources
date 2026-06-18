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

class LnMTLProvider : MainProvider() {

    override val name = "LnMTL"
    override val mainUrl = "https://lnmtl.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=lnmtl.com&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Most Favourite", "favourite"),
        FilterOption("Most Followed", "follow"),
        FilterOption("Most Viewed", "view"),
        FilterOption("Recently Updated", "updated"),
        FilterOption("Newest", "created")
    )

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val order = orderBy.takeUnless { it.isNullOrEmpty() } ?: "favourite"
        val url = "$mainUrl/novel?orderBy=$order&order=desc&filter=all&page=$page"
        val document = get(url).document
        val novels = parseBrowsePage(document)
        return MainPageResult(url = url, novels = novels)
    }

    private fun parseBrowsePage(document: Document): List<Novel> {
        return document.select("div.media").mapNotNull { element ->
            parseMediaElement(element)
        }
    }

    private fun parseMediaElement(element: Element): Novel? {
        val titleLink = element.selectFirstOrNull("h3.media-heading > a") ?: return null
        val name = titleLink.textOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = titleLink.attrOrNull("href") ?: return null
        val novelUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return null
        val imgElement = element.selectFirstOrNull("img")
        val posterUrl = imgElement?.attrOrNull("src") ?: imgElement?.attrOrNull("data-src")
        return Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/novel?orderBy=favourite&order=desc&filter=all&page=1&search=$encodedQuery"
        val document = get(url).document
        return parseBrowsePage(document)
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val response = get(fullUrl)
        val document = response.document
        val responseText = response.text
        val name = document.selectFirstOrNull("h2.novel-name")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("h1")?.textOrNull()?.trim()
            ?: return null
        val posterUrl = document.selectFirstOrNull("div.novel-image > img")?.attrOrNull("src")
        val synopsis = document.selectFirstOrNull("div.novel-description")?.textOrNull()?.trim()
        val author = document.selectFirstOrNull("div.novel-detail-item a[href*='/author/'], a[itemprop='author']")
            ?.textOrNull()?.trim()
        val tags = document.select("a[href*='/genre/'], a[href*='/tag/']")
            .mapNotNull { it.textOrNull()?.trim() }.filter { it.isNotBlank() }.distinct()
        val status = document.selectFirstOrNull("div.novel-detail-item span.label")
            ?.textOrNull()?.trim()?.replaceFirstChar { it.uppercase() }
        val chapters = loadAllChapters(responseText, fullUrl)
        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            author = author, posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, status = status
        )
    }

    private suspend fun loadAllChapters(responseText: String, novelUrl: String): List<Chapter> {
        val volumes = extractVolumes(responseText)
        if (volumes.isEmpty()) return emptyList()
        val allChapters = mutableListOf<Chapter>()
        for (volume in volumes) {
            val volumeChapters = loadVolumeChapters(volume.id)
            allChapters.addAll(volumeChapters)
        }
        return allChapters
    }

    private data class VolumeInfo(val id: Int, val title: String, val order: Int)

    private fun extractVolumes(responseText: String): List<VolumeInfo> {
        return try {
            val match = Regex("""lnmtl\.volumes\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
                .find(responseText) ?: return emptyList()
            val jsonArray = JSONArray(match.groupValues[1])
            val volumes = mutableListOf<VolumeInfo>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val id = obj.optInt("id", -1).takeIf { it >= 0 } ?: continue
                val title = obj.optString("title", "Volume ${i + 1}")
                val order = obj.optInt("order", i + 1)
                volumes.add(VolumeInfo(id = id, title = title, order = order))
            }
            volumes.sortedBy { it.order }
        } catch (_: Throwable) { emptyList() }
    }

    private suspend fun loadVolumeChapters(volumeId: Int): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var page = 1
        var hasMore = true
        while (hasMore) {
            try {
                val url = "$mainUrl/chapter?volumeId=$volumeId&page=$page"
                val document = get(url).document
                val chapterLinks = document.select("div.chapter-index > a")
                if (chapterLinks.isEmpty()) break
                for (link in chapterLinks) {
                    val href = link.attrOrNull("href") ?: continue
                    val chapterUrl = if (href.startsWith("http")) href else fixUrl(href) ?: continue
                    val chapterName = link.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
                        ?: link.attrOrNull("title")?.trim()?.takeIf { it.isNotBlank() }
                        ?: "Chapter ${chapters.size + 1}"
                    chapters.add(Chapter(name = chapterName, url = chapterUrl))
                }
                val lastPaginationLink = document.selectFirstOrNull("div.pagination a:last-child")
                val nextHref = lastPaginationLink?.attrOrNull("href")
                hasMore = nextHref != null && !nextHref.contains("page=$page")
                page++
            } catch (_: Throwable) {
                break
            }
        }
        return chapters
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        val sentences = document.select("sentence.translated")
        if (sentences.isEmpty()) return null
        val sb = StringBuilder()
        for (sentence in sentences) {
            val text = sentence.textOrNull()?.trim() ?: continue
            if (text.isNotBlank()) {
                sb.append("<p>").append(text).append("</p>\n")
            }
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }
}
