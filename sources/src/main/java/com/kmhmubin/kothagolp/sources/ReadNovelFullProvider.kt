package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import org.jsoup.nodes.Element

class ReadNovelFullProvider : MainProvider() {

    override val name = "ReadNovelFull"
    override val mainUrl = "https://readnovelfull.com"
    override val hasMainPage = false

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult = MainPageResult(url = mainUrl, novels = emptyList())

    override suspend fun search(query: String): List<Novel> {
        val document = get(
            "$mainUrl/novel-list/search?keyword=$query",
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        ).document

        val headers = document.select("div.col-novel-main > div.list-novel > div.row")
        return headers.mapNotNull { h ->
            val divs = h.select("> div > div")
            val poster = divs.getOrNull(0)?.selectFirstOrNull("> img")?.attrOrNull("src")
                ?.replace("t-200x89", "t-300x439")
            val titleHeader = divs.getOrNull(1)?.selectFirstOrNull("> h3.novel-title > a")
            val href = titleHeader?.attrOrNull("href") ?: return@mapNotNull null
            val title = titleHeader.textOrNull() ?: return@mapNotNull null
            Novel(name = title, url = fixUrl(href) ?: return@mapNotNull null, posterUrl = poster?.let { fixUrl(it) }, apiName = this.name)
        }
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document

        val header = document.selectFirstOrNull("div.col-info-desc")
        val bookInfo = header?.selectFirstOrNull("> div.info-holder > div.books")
        val title = bookInfo?.selectFirstOrNull("> div.desc > h3.title")?.textOrNull() ?: return null

        val desc = header?.selectFirstOrNull("> div.desc")
        val rateInfo = desc?.selectFirstOrNull("> div.rate-info")
        val rate = rateInfo?.selectFirstOrNull("> div.rate")
        val novelId = rate?.selectFirstOrNull("> div#rating")?.attrOrNull("data-novel-id")
            ?: return null

        val infoMetas = desc?.select("> ul.info-meta > li") ?: listOf()

        fun getData(valueId: String): Element? {
            for (item in infoMetas) {
                if (item.selectFirstOrNull("> h3")?.textOrNull() == valueId) return item
            }
            return null
        }

        val dataUrl = "$mainUrl/ajax/chapter-archive?novelId=$novelId"
        val dataDoc = get(dataUrl).document
        val chapters = dataDoc.select("div.panel-body > div.row > div > ul.list-chapter > li > a")
            .mapNotNull { el ->
                val chapterName = el.selectFirstOrNull("> span")?.textOrNull() ?: return@mapNotNull null
                val href = el.attrOrNull("href") ?: return@mapNotNull null
                Chapter(name = chapterName, url = fixUrl(href) ?: return@mapNotNull null)
            }

        val author = getData("Author:")?.selectFirstOrNull("> a")?.textOrNull()
        val tags = getData("Genre:")?.select("> a")?.mapNotNull { it.textOrNull()?.trim() }
        val statusText = getData("Status:")?.selectFirstOrNull("> a")?.textOrNull()
        val status = parseStatus(statusText)
        val synopsis = document.selectFirstOrNull("div.desc-text")?.textOrNull()
        val rating = rate?.selectFirstOrNull("> input")?.attrOrNull("value")?.toFloatOrNull()
            ?.let { (it * 100f).toInt().coerceIn(0, 1000) }
        val peopleVoted = rateInfo?.select("> div.small > em > strong > span")?.lastOrNull()?.textOrNull()?.toIntOrNull()
        val posterUrl = bookInfo?.selectFirstOrNull("> div.book > img")?.attrOrNull("src")?.let { fixUrl(it) }

        return NovelDetails(
            url = fullUrl, name = title, chapters = chapters, author = author,
            posterUrl = posterUrl, synopsis = synopsis,
            tags = tags?.ifEmpty { null }, rating = rating,
            peopleVoted = peopleVoted, status = status
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

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = get(fullUrl).document
        return document.selectFirstOrNull("div#chr-content")?.html()
            ?.replace("[Updated from F r e e w e b n o v e l. c o m]", "")
            ?.replace("Freewebnovel.com", "")
    }
}
