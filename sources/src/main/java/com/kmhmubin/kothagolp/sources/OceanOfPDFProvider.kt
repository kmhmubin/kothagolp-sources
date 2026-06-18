package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class OceanOfPDFProvider : MainProvider() {

    override val name = "OceanOfPDF"
    override val mainUrl = "https://oceanofpdf.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=oceanofpdf.com&sz=64"
    override val hasMainPage = true

    override val orderBys = listOf(
        FilterOption("Latest", ""),
        FilterOption("Popular", "popular")
    )

    private fun fixPosterUrl(imgElement: Element?): String? {
        if (imgElement == null) return null
        val rawSrc = imgElement.attrOrNull("data-src")
            ?: imgElement.attrOrNull("src") ?: return null
        if (rawSrc.isBlank() || rawSrc.contains("data:image")) return null
        return if (rawSrc.startsWith("http")) rawSrc else "$mainUrl$rawSrc"
    }

    private fun parseArticles(document: Document): List<Novel> {
        return document.select("article.post").mapNotNull { article ->
            val titleElement = article.selectFirstOrNull("h2.entry-title > a")
                ?: article.selectFirstOrNull("h1.entry-title > a")
                ?: return@mapNotNull null
            val name = titleElement.textOrNull()?.trim() ?: return@mapNotNull null
            val href = titleElement.attrOrNull("href") ?: return@mapNotNull null
            val novelUrl = if (href.startsWith("http")) href else fixUrl(href) ?: return@mapNotNull null
            val imgElement = article.selectFirstOrNull("div.post-thumbnail > a > img")
                ?: article.selectFirstOrNull("div.post-thumbnail img")
                ?: article.selectFirstOrNull("img")
            val posterUrl = fixPosterUrl(imgElement)
            Novel(name = name, url = novelUrl, posterUrl = posterUrl, apiName = this.name)
        }
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val url = if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val document = get(url).document
        val novels = parseArticles(document)
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encodedQuery"
        val document = get(url).document
        return parseArticles(document)
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document

        val name = document.selectFirstOrNull("h1.entry-title")?.textOrNull()?.trim()
            ?: document.selectFirstOrNull("h2.entry-title")?.textOrNull()?.trim()
            ?: return null

        val imgElement = document.selectFirstOrNull("div.entry-content img:first-child")
            ?: document.selectFirstOrNull("div.post-thumbnail img")
            ?: document.selectFirstOrNull("div.entry-content img")
        val posterUrl = fixPosterUrl(imgElement)

        val synopsis = document.select("div.entry-content > p")
            .take(5)
            .mapNotNull { it.textOrNull()?.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

        val chapters = extractChapters(document, fullUrl)

        return NovelDetails(
            url = fullUrl, name = name, chapters = chapters,
            posterUrl = posterUrl, synopsis = synopsis
        )
    }

    private fun extractChapters(document: Document, articleUrl: String): List<Chapter> {
        val downloadLinks = document.select("div.entry-content a[href*=pdf], div.entry-content a[href*=download]")
        if (downloadLinks.isNotEmpty()) {
            return if (downloadLinks.size == 1) {
                val href = downloadLinks.first()!!.attrOrNull("href") ?: articleUrl
                listOf(Chapter(name = "Download", url = href))
            } else {
                downloadLinks.mapIndexed { index, element ->
                    val href = element.attrOrNull("href") ?: articleUrl
                    val chapterName = element.textOrNull()?.trim()?.takeIf { it.isNotBlank() }
                        ?: "Download ${index + 1}"
                    Chapter(name = chapterName, url = href)
                }
            }
        }
        return listOf(Chapter(name = "Read / Download", url = articleUrl))
    }

    override suspend fun loadChapterContent(url: String): String? {
        return try {
            val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
            val document = get(fullUrl).document
            val contentElement = document.selectFirstOrNull("div.entry-content") ?: return null
            contentElement.select(".adsbygoogle, script, style, ins, [class*=ads]").remove()
            contentElement.html().trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }
}
