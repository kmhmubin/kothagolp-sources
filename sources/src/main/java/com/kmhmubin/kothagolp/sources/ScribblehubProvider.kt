package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider

class ScribblehubProvider : MainProvider() {

    override val name = "Scribblehub"
    override val mainUrl = "https://www.scribblehub.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=scribblehub.com&sz=64"
    override val hasMainPage = false
    override val rateLimitTime: Long = 500L

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult = MainPageResult(url = mainUrl, novels = emptyList())

    override suspend fun search(query: String): List<Novel> {
        val url = "$mainUrl/?s=$query&post_type=fictionposts"
        val document = get(url).document
        return document.select("div.search_main_box").mapNotNull { item ->
            val img = item.selectFirstOrNull("> div.search_img > img")?.attrOrNull("src")
            val body = item.selectFirstOrNull("> div.search_body > div.search_title > a")
            val title = body?.textOrNull() ?: return@mapNotNull null
            val href = body.attrOrNull("href") ?: return@mapNotNull null
            Novel(name = title, url = href, posterUrl = img, apiName = this.name)
        }
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val seriesId = Regex("series/([0-9]*?)/").find(fullUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: throw Exception("Could not extract series ID from URL: $fullUrl")

        val document = get(fullUrl).document

        val listResponse = post(
            url = "https://www.scribblehub.com/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "wi_getreleases_pagination",
                "pagenum" to "1",
                "mypostid" to "$seriesId"
            )
        )

        val listDoc = org.jsoup.Jsoup.parse(listResponse.text)
        val items = listDoc.select("ol.toc_ol > li")
        val chapters = items.mapIndexedNotNull { index, element ->
            val aHeader = element.selectFirstOrNull("> a")
            val href = aHeader?.attrOrNull("href") ?: return@mapIndexedNotNull null
            val date = element.selectFirstOrNull("> span")?.textOrNull()
            val chapterName = aHeader.ownText().trim().takeIf { it.isNotBlank() } ?: "Chapter $index"
            Chapter(name = chapterName, url = href, dateOfRelease = date)
        }

        val title = document.selectFirstOrNull("div.fic_title")?.textOrNull() ?: return null
        val posterUrl = document.selectFirstOrNull("div.fic_image > img")?.attrOrNull("src")?.let { fixUrl(it) }
        val synopsis = document.selectFirstOrNull("div.wi_fic_desc")?.textOrNull()
        val tags = document.select("span.wi_fic_genre > span > a.fic_genre").mapNotNull { it.textOrNull()?.trim() }
        val author = document.selectFirstOrNull("span.auth_name_fic")?.textOrNull()

        val ratings = document.select("span#ratefic_user > span > span")
        val ratingRaw = ratings.firstOrNull()?.textOrNull()?.toFloatOrNull()
        val rating = ratingRaw?.let { (it * 200f).toInt().coerceIn(0, 1000) }
        val peopleVoted = ratings.getOrNull(1)?.selectFirstOrNull("> span")?.textOrNull()
            ?.replace(" ratings", "")?.toIntOrNull()

        val statusSpan = document.selectFirstOrNull("ul.widget_fic_similar > li > span")
            ?.lastElementSibling()?.ownText()
        val status = parseStatus(statusSpan?.substringBefore("-")?.trim())

        return NovelDetails(
            url = fullUrl, name = title, chapters = chapters, author = author,
            posterUrl = posterUrl, synopsis = synopsis,
            tags = tags.ifEmpty { null }, rating = rating,
            peopleVoted = peopleVoted, status = status
        )
    }

    private fun parseStatus(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        return when (statusText.lowercase().trim()) {
            "ongoing" -> "Ongoing"
            "completed" -> "Completed"
            "hiatus" -> "On Hiatus"
            "dropped" -> "Dropped"
            else -> null
        }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        return document.selectFirstOrNull("div#chp_raw")?.html()
    }
}
