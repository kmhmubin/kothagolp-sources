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

/**
 * Provider for wtr-lab.com
 *
 * CLOUDFLARE PROTECTION: This site uses Cloudflare Turnstile protection.
 * Users must solve the challenge in WebView before reading.
 * Cookies are managed by the host app's NetworkClient (which injects CF cookies automatically).
 *
 * DECRYPTION: Chapter content is AES-GCM encrypted. This provider calls the API to get
 * pre-decrypted content using the "web" or "ai" translation service endpoints.
 * The decryption key extraction requires javax.crypto which is available on Android.
 */
class WtrLabProvider : MainProvider() {

    override val name = "WTR-LAB"
    override val mainUrl = "https://wtr-lab.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=wtr-lab.com&sz=64"
    override val hasMainPage = true
    override val rateLimitTime: Long = 3000L

    private val lang = "en"
    private var cachedBuildId: String? = null

    companion object {
        private const val BATCH_SIZE = 250
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private object Endpoints {
            const val API_CHAPTERS = "/api/chapters"
            const val API_READER_GET = "/api/reader/get"
            const val NOVEL_FINDER = "/en/novel-finder"
        }

        private object Status {
            const val ONGOING = 0
            const val COMPLETED = 1
            const val HIATUS = 2
            const val DROPPED = 3
        }
    }

    override val orderBys = listOf(
        FilterOption("Update Date", "update"),
        FilterOption("Addition Date", "date"),
        FilterOption("Weekly View", "weekly_rank"),
        FilterOption("Monthly View", "monthly_rank"),
        FilterOption("All-Time View", "view"),
        FilterOption("Chapter Count", "chapter"),
        FilterOption("Rating", "rating"),
        FilterOption("Name", "name")
    )

    override val tags = listOf(
        FilterOption("Male Protagonist", "417"),
        FilterOption("Female Protagonist", "275"),
        FilterOption("Transmigration", "717"),
        FilterOption("System", "696"),
        FilterOption("Cultivation", "169"),
        FilterOption("Reincarnation", "578"),
        FilterOption("Fantasy World", "265"),
        FilterOption("Overpowered Protagonist", "506"),
        FilterOption("Weak to Strong", "750"),
        FilterOption("Romance", "592"),
        FilterOption("Action", "1"),
        FilterOption("Adventure", "2"),
        FilterOption("Comedy", "3"),
        FilterOption("Drama", "4"),
        FilterOption("Fantasy", "5"),
        FilterOption("Harem", "6"),
        FilterOption("Martial Arts", "426"),
        FilterOption("Sci-fi", "13"),
        FilterOption("Xianxia", "20"),
        FilterOption("Xuanhuan", "21"),
        FilterOption("Game Elements", "297"),
        FilterOption("Kingdom Building", "379"),
        FilterOption("Time Travel", "710"),
        FilterOption("Apocalypse", "47"),
        FilterOption("Magic", "410"),
        FilterOption("Fanfiction", "263")
    )

    private fun buildHeaders(isApiRequest: Boolean = false): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = DESKTOP_USER_AGENT
        if (isApiRequest) {
            headers["Accept"] = "application/json, text/plain, */*"
        } else {
            headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        }
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["Connection"] = "keep-alive"
        return headers
    }

    private fun extractNextData(document: Document): JSONObject? {
        val script = document.selectFirstOrNull("script#__NEXT_DATA__")
        val jsonText = script?.data() ?: return null
        return try { JSONObject(jsonText) } catch (_: Exception) { null }
    }

    private fun extractBuildId(nextData: JSONObject): String? =
        nextData.optString("buildId", null)?.takeIf { it.isNotBlank() }

    private fun parseStatus(status: Int?): String? = when (status) {
        Status.ONGOING -> "Ongoing"
        Status.COMPLETED -> "Completed"
        Status.HIATUS -> "Hiatus"
        Status.DROPPED -> "Dropped"
        else -> null
    }

    private suspend fun getBuildId(): String {
        cachedBuildId?.let { return it }
        val response = get("$mainUrl${Endpoints.NOVEL_FINDER}", buildHeaders())
        if (response.isCloudflareBlocked) throw Exception("Cloudflare verification required. Please open in WebView.")
        val nextData = extractNextData(response.document)
        val buildId = nextData?.let { extractBuildId(it) }
            ?: throw Exception("Could not extract buildId from page")
        cachedBuildId = buildId
        return buildId
    }

    private fun parseNovelFromJson(seriesObj: JSONObject): Novel? {
        val rawId = seriesObj.optLong("raw_id", 0)
        if (rawId == 0L) return null
        val slug = seriesObj.optString("slug", "")
        val data = seriesObj.optJSONObject("data") ?: return null
        val title = data.optString("title", "").takeIf { it.isNotBlank() } ?: return null
        val image = data.optString("image", null)?.takeIf { it.isNotBlank() }
        val novelUrl = "/$lang/serie-$rawId/$slug"
        val chapterCount = seriesObj.optInt("chapter_count", 0)
        val latestChapter = if (chapterCount > 0) "$chapterCount Chapters" else null
        val rating = seriesObj.optDouble("rating", Double.NaN)
            .takeIf { !it.isNaN() && it > 0 }
            ?.let { (it.toFloat() / 5f * 1000f).toInt().coerceIn(0, 1000) }
        return Novel(name = title, url = fixUrl(novelUrl) ?: return null, posterUrl = image, latestChapter = latestChapter, rating = rating, apiName = this.name)
    }

    override suspend fun loadMainPage(
        page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
    ): MainPageResult {
        val buildId = getBuildId()
        val params = buildList {
            add("orderBy=${orderBy ?: "update"}")
            add("order=desc")
            add("status=all")
            add("release_status=all")
            add("addition_age=all")
            add("page=$page")
            if (!tag.isNullOrEmpty()) { add("gi=$tag"); add("gc=or") }
        }
        val url = "$mainUrl/_next/data/$buildId/$lang/novel-finder.json?${params.joinToString("&")}"
        val response = get(url, buildHeaders(isApiRequest = true))
        if (response.isCloudflareBlocked) throw Exception("Cloudflare verification required. Please open in WebView.")
        val json = JSONObject(response.text)
        val seriesArray = json.optJSONObject("pageProps")?.optJSONArray("series")
            ?: return MainPageResult(url, emptyList())
        val novels = mutableListOf<Novel>()
        val seenIds = mutableSetOf<Long>()
        for (i in 0 until seriesArray.length()) {
            val obj = seriesArray.getJSONObject(i)
            val rawId = obj.optLong("raw_id", 0)
            if (rawId != 0L && seenIds.add(rawId)) parseNovelFromJson(obj)?.let { novels.add(it) }
        }
        return MainPageResult(url = url, novels = novels)
    }

    override suspend fun search(query: String): List<Novel> {
        val buildId = getBuildId()
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/_next/data/$buildId/$lang/novel-finder.json?text=$encodedQuery"
        val response = get(url, buildHeaders(isApiRequest = true))
        if (response.isCloudflareBlocked) throw Exception("Cloudflare verification required. Please open in WebView.")
        val json = JSONObject(response.text)
        val seriesArray = json.optJSONObject("pageProps")?.optJSONArray("series") ?: return emptyList()
        val novels = mutableListOf<Novel>()
        val seenIds = mutableSetOf<Long>()
        for (i in 0 until seriesArray.length()) {
            val obj = seriesArray.getJSONObject(i)
            val rawId = obj.optLong("raw_id", 0)
            if (rawId != 0L && seenIds.add(rawId)) parseNovelFromJson(obj)?.let { novels.add(it) }
        }
        return novels
    }

    override suspend fun load(url: String): NovelDetails? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val response = get(fullUrl, buildHeaders())
        if (response.isCloudflareBlocked) throw Exception("Cloudflare verification required. Please open in WebView.")
        val document = response.document
        val nextData = extractNextData(document) ?: return null
        val props = nextData.optJSONObject("props") ?: return null
        val pageProps = props.optJSONObject("pageProps") ?: return null
        val serie = pageProps.optJSONObject("serie") ?: return null
        val serieData = serie.optJSONObject("serie_data") ?: return null
        val rawId = serieData.optLong("raw_id", 0)
        if (rawId == 0L) return null
        val slug = serieData.optString("slug", "")
        val data = serieData.optJSONObject("data")
        val title = data?.optString("title")?.takeIf { it.isNotBlank() } ?: return null
        val author = data.optString("author")?.takeIf { it.isNotBlank() }
        val description = data.optString("description")?.takeIf { it.isNotBlank() }
        val image = data.optString("image")?.takeIf { it.isNotBlank() }
        val status = parseStatus(serieData.optInt("status", -1))
        val rawChapterCount = serieData.optLong("raw_chapter_count", 0)
        val rating = serieData.optDouble("rating", Double.NaN)
            .takeIf { !it.isNaN() && it > 0 }
            ?.let { (it.toFloat() / 5f * 1000f).toInt().coerceIn(0, 1000) }
        val peopleVoted = serieData.optInt("total_rate", 0).takeIf { it > 0 }
        val views = serieData.optInt("view", 0).takeIf { it > 0 }
        val tags = mutableListOf<String>()
        val tagsArray = pageProps.optJSONArray("tags")
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val tagTitle = tagsArray.optJSONObject(i)?.optString("title")
                if (!tagTitle.isNullOrBlank()) tags.add(tagTitle)
            }
        }
        val chapters = loadChapters(rawId, rawChapterCount, slug)
        val relatedNovels = serie.optJSONArray("recommendation")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> parseNovelFromJson(arr.getJSONObject(i)) }
        } ?: emptyList()
        return NovelDetails(
            url = fullUrl, name = title, chapters = chapters,
            author = author, posterUrl = image, synopsis = description ?: "No description available.",
            tags = tags.ifEmpty { null }, rating = rating, peopleVoted = peopleVoted,
            status = status, views = views, relatedNovels = relatedNovels.ifEmpty { null }
        )
    }

    private suspend fun loadChapters(rawId: Long, totalChapters: Long, slug: String): List<Chapter> {
        if (totalChapters <= 0) return emptyList()
        val chapters = mutableListOf<Chapter>()
        var start = 1L
        while (start <= totalChapters) {
            val end = minOf(start + BATCH_SIZE - 1, totalChapters)
            try {
                val url = "$mainUrl${Endpoints.API_CHAPTERS}/$rawId?start=$start&end=$end"
                val json = JSONObject(get(url, buildHeaders(isApiRequest = true)).text)
                val chaptersArray = json.optJSONArray("chapters") ?: break
                if (chaptersArray.length() == 0) break
                for (i in 0 until chaptersArray.length()) {
                    val obj = chaptersArray.getJSONObject(i)
                    val order = obj.optLong("order", 0)
                    val chTitle = obj.optString("title", "").trim()
                    val updatedAt = obj.optString("updated_at", null)
                    val chapterName = if (chTitle.isNotBlank()) "#$order: $chTitle" else "Chapter $order"
                    val chapterUrl = "/$lang/serie-$rawId/$slug/chapter-$order"
                    chapters.add(Chapter(name = chapterName, url = fixUrl(chapterUrl) ?: continue, dateOfRelease = updatedAt?.take(10)))
                }
                if (chaptersArray.length() < BATCH_SIZE) break
                start += BATCH_SIZE
            } catch (_: Exception) { break }
        }
        return chapters.sortedBy { Regex("chapter-(\\d+)").find(it.url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0 }
    }

    override suspend fun loadChapterContent(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val regex = Regex("/serie-(\\d+)/[^/]+/chapter-(\\d+)")
        val match = regex.find(fullUrl) ?: throw Exception("Invalid chapter URL format: $fullUrl")
        val rawId = match.groupValues[1]
        val chapterNo = match.groupValues[2]

        val services = listOf("ai", "web")
        var lastError: String? = null

        for (service in services) {
            try {
                val jsonBody = JSONObject().apply {
                    put("translate", service)
                    put("language", lang)
                    put("raw_id", rawId.toLong())
                    put("chapter_no", chapterNo.toLong())
                    put("retry", false)
                    put("force_retry", false)
                }
                val response = postJson(
                    url = "$mainUrl${Endpoints.API_READER_GET}",
                    json = jsonBody.toString(),
                    headers = buildHeaders(isApiRequest = true) + mapOf("Referer" to fullUrl, "Origin" to mainUrl)
                )
                if (response.isCloudflareBlocked) {
                    return buildCloudflareErrorHtml()
                }
                val json = JSONObject(response.text)
                if (json.optBoolean("requireTurnstile", false)) return buildCloudflareErrorHtml()
                if (json.optString("code", null) == "CHAPTER_LOCKED") {
                    lastError = "Chapter is locked or not translated yet."
                    continue
                }
                val code = json.optInt("code", 0)
                if (code == 401 || code == 403) { lastError = "Authentication required."; continue }
                if (!json.optBoolean("success", true)) {
                    lastError = json.optString("message", "Unknown error"); continue
                }
                val dataObj = json.optJSONObject("data")?.optJSONObject("data") ?: continue
                val bodyContent = dataObj.opt("body") ?: continue

                val paragraphs: List<String> = when (bodyContent) {
                    is String -> listOf(bodyContent)
                    is JSONArray -> (0 until bodyContent.length()).map { bodyContent.getString(it) }
                    else -> { lastError = "Unexpected body type"; continue }
                }

                // Build HTML from paragraphs
                val html = StringBuilder()
                val chapterTitle = json.optJSONObject("chapter")?.optString("title")
                if (!chapterTitle.isNullOrBlank()) html.append("<h1>#$chapterNo: $chapterTitle</h1>\n")
                if (service == "web") {
                    html.append("<p><em>[Machine Translation - AI translation unavailable]</em></p>\n")
                }
                val images = dataObj.optJSONArray("images")
                var imageIndex = 0
                for (paragraph in paragraphs) {
                    if (paragraph == "[image]") {
                        val imageUrl = images?.optString(imageIndex)
                        if (!imageUrl.isNullOrBlank()) html.append("""<p><img src="$imageUrl" /></p>""" + "\n")
                        imageIndex++
                    } else {
                        html.append("<p>$paragraph</p>\n")
                    }
                }
                return html.toString()
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                continue
            }
        }
        return buildErrorHtml(lastError)
    }

    private fun buildCloudflareErrorHtml(): String = buildString {
        append("<div style=\"padding: 20px; text-align: center;\">")
        append("<h2>Cloudflare Protection Active</h2>")
        append("<p>WTR-LAB uses Cloudflare protection. Please open the site in WebView to solve the challenge first, then return to reading.</p>")
        append("</div>")
    }

    private fun buildErrorHtml(errorMessage: String?): String = buildString {
        append("<div style=\"padding: 20px; text-align: center;\">")
        append("<h2>Failed to load chapter</h2>")
        append("<p>${errorMessage ?: "Unknown error occurred"}</p>")
        append("</div>")
    }
}
