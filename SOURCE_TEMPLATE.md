# Source Provider Template & Developer Guide

Reference for building, fixing, or updating source providers in **kothagolp-sources**.
Every provider is a single `.kt` file in `sources/src/main/java/com/kmhmubin/kothagolp/sources/`.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Data Models](#data-models)
3. [NetworkClient](#networkclient)
4. [Minimal Provider Skeleton](#minimal-provider-skeleton)
5. [Method Contract](#method-contract)
6. [Helper Methods (from MainProvider)](#helper-methods-from-mainprovider)
7. [HTML Scraping Pattern](#html-scraping-pattern)
8. [REST API Pattern](#rest-api-pattern)
9. [Common Pitfalls & Fixes](#common-pitfalls--fixes)
10. [Binary / EPUB Download Pattern](#binary--epub-download-pattern)
11. [Testing](#testing)
12. [Adding a New Provider Checklist](#adding-a-new-provider-checklist)
13. [Provider Reference Table](#provider-reference-table)

---

## Architecture Overview

```
host app (runtime)
  └── loads sources.apk via DexClassLoader
        └── each XxxProvider : MainProvider()
              └── calls NetworkClient.get/post (stub replaced by host at runtime)
```

- **`source-api` module** — shared contracts only (`MainProvider`, models, `NetworkClient` stub). Declared `compileOnly` so it is NOT bundled in the APK.
- **`sources` module** — all providers + `implementation` deps (Jsoup, OkHttp3, kotlinx-coroutines). These ARE bundled in the APK.
- **Test `NetworkClient`** — real OkHttp3 implementation that shadows the stub at test time.

---

## Data Models

```kotlin
// Novel card shown in browse/search lists
data class Novel(
    val name: String,
    val url: String,                  // canonical URL for this novel — passed to load()
    val posterUrl: String? = null,
    val rating: Int? = null,
    val latestChapter: String? = null,
    val apiName: String = ""          // always set to this.name
)

// Full novel detail page
data class NovelDetails(
    val url: String,
    val name: String,
    val chapters: List<Chapter>,      // must NOT be empty for test to pass
    val author: String? = null,
    val posterUrl: String? = null,
    val synopsis: String? = null,
    val tags: List<String>? = null,   // genres/tags combined; null if none
    val rating: Int? = null,
    val peopleVoted: Int? = null,
    val status: String? = null,       // "Ongoing" / "Completed" / "Hiatus" etc.
    val views: Int? = null,
    val relatedNovels: List<Novel>? = null
)

// Single chapter entry
data class Chapter(
    val name: String,
    val url: String,                  // passed to loadChapterContent()
    val dateOfRelease: String? = null
)

// Return type of loadMainPage()
data class MainPageResult(
    val url: String,                  // URL that was fetched
    val novels: List<Novel>,          // must NOT be empty for test to pass
    val hasNextPage: Boolean = true
)

// Filter dropdown option
data class FilterOption(
    val name: String,   // display label shown to user
    val value: String   // value sent to the site (URL param, query string, etc.)
)
```

---

## NetworkClient

```kotlin
// Available inside any MainProvider via inherited get() / post() / postJson()
protected suspend fun get(url: String, headers: Map<String, String> = emptyMap()): NetworkResponse
protected suspend fun post(url: String, data: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): NetworkResponse
protected suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): NetworkResponse

data class NetworkResponse(
    val document: Document,          // Jsoup-parsed HTML (use for HTML scraping)
    val text: String,                // raw response body as String (use for JSON APIs)
    val isSuccessful: Boolean,
    val code: Int,
    val isCloudflareBlocked: Boolean,
    val headers: Map<String, String>
)
```

**Important:** `NetworkClient` is a stub in the APK — host replaces it at runtime. It is real (OkHttp3) only in tests. Do NOT instantiate `OkHttpClient` inside a provider unless you need binary/byte responses (see [Binary / EPUB Download Pattern](#binary--epub-download-pattern)).

---

## Minimal Provider Skeleton

```kotlin
package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.*
import com.kmhmubin.kothagolp.provider.MainProvider
import org.json.JSONArray
import org.json.JSONObject

class ExampleProvider : MainProvider() {

    override val name = "Example"                               // display name
    override val mainUrl = "https://example.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=example.com&sz=64"
    override val hasMainPage = true                             // false = no browse tab

    override val orderBys = listOf(
        FilterOption("Latest", "latest"),
        FilterOption("Popular", "popular")
    )

    override val tags = listOf(
        FilterOption("All", ""),
        FilterOption("Fantasy", "fantasy"),
        FilterOption("Romance", "romance")
        // ...
    )

    override suspend fun loadMainPage(
        page: Int,
        orderBy: String? = null,
        tag: String? = null,
        extraFilters: Map<String, String> = emptyMap()
    ): MainPageResult { TODO() }

    override suspend fun search(query: String): List<Novel> { TODO() }

    override suspend fun load(url: String): NovelDetails? { TODO() }

    override suspend fun loadChapterContent(url: String): String? { TODO() }
}
```

---

## Method Contract

| Method | Must return | Failure behaviour |
|--------|-------------|-------------------|
| `loadMainPage` | `MainPageResult` with `novels.isNotEmpty()` | Return `MainPageResult(url, emptyList())` — never throw |
| `search` | `List<Novel>` with at least 1 result | Return `emptyList()` — never throw |
| `load` | `NovelDetails?` with `chapters.isNotEmpty()` | Return `null` on hard failure |
| `loadChapterContent` | Non-blank HTML string | Return `null` — never throw |

**Tests assert:** main page ≥ 1 novel, search ≥ 1 result, `load()` ≠ null, chapters ≠ empty, chapter content ≠ blank.

### loadMainPage

```kotlin
override suspend fun loadMainPage(
    page: Int, orderBy: String?, tag: String?, extraFilters: Map<String, String>
): MainPageResult {
    val sort = orderBy?.takeIf { it.isNotBlank() } ?: "latest"  // always provide default
    val genre = tag?.takeIf { it.isNotBlank() }
    val url = "$mainUrl/browse?page=$page&sort=$sort" +
              if (!genre.isNullOrBlank()) "&genre=$genre" else ""
    return try {
        val document = get(url).document
        val novels = document.select("div.novel-card").mapNotNull { card ->
            val title = card.selectFirstOrNull("h3.title")?.textOrNull() ?: return@mapNotNull null
            val href  = card.selectFirstOrNull("a")?.attrOrNull("href") ?: return@mapNotNull null
            val cover = card.selectFirstOrNull("img")?.attrOrNull("src")
                ?.let { fixUrl(it) }
            Novel(name = title, url = fixUrl(href)!!, posterUrl = cover, apiName = this.name)
        }
        val hasNext = document.selectFirstOrNull("a.next, a[rel=next]") != null
        MainPageResult(url = url, novels = novels, hasNextPage = hasNext)
    } catch (_: Throwable) { MainPageResult(url = url, novels = emptyList()) }
}
```

### search

```kotlin
override suspend fun search(query: String): List<Novel> {
    val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
    val url = "$mainUrl/search?q=$encoded"
    return try {
        val document = get(url).document
        document.select("div.novel-card").mapNotNull { card ->
            val title = card.selectFirstOrNull("h3")?.textOrNull() ?: return@mapNotNull null
            val href  = card.selectFirstOrNull("a")?.attrOrNull("href") ?: return@mapNotNull null
            Novel(name = title, url = fixUrl(href)!!, apiName = this.name)
        }
    } catch (_: Throwable) { emptyList() }
}
```

### load

```kotlin
override suspend fun load(url: String): NovelDetails? {
    val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
    val document = get(fullUrl).document

    val title = document.selectFirstOrNull("h1.novel-title")?.textOrNull() ?: return null
    val cover = document.selectFirstOrNull("meta[property='og:image']")?.attrOrNull("content")
    val synopsis = document.selectFirstOrNull("div.synopsis")?.textOrNull()
    val author = document.selectFirstOrNull("span.author")?.textOrNull()
    val status = document.selectFirstOrNull("span.status")?.textOrNull()
    val tags = document.select("a.tag").mapNotNull { it.textOrNull()?.trim() }

    val chapters = document.select("ul.chapter-list li a").mapNotNull { a ->
        val href = a.attrOrNull("href") ?: return@mapNotNull null
        val name = a.textOrNull()?.trim() ?: return@mapNotNull null
        Chapter(name = name, url = fixUrl(href)!!)
    }

    return NovelDetails(
        url = fullUrl, name = title, chapters = chapters,
        author = author, posterUrl = cover, synopsis = synopsis,
        tags = tags.ifEmpty { null }, status = status
    )
}
```

### loadChapterContent

```kotlin
override suspend fun loadChapterContent(url: String): String? {
    val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
    val document = get(fullUrl).document
    val content = document.selectFirstOrNull("div.chapter-content") ?: return null
    content.select("script, style, .ads, iframe").remove()
    return content.html().trim().takeIf { it.isNotBlank() }
}
```

---

## Helper Methods (from MainProvider)

These are available inside every provider — no import needed:

```kotlin
// Resolve relative URLs safely
fixUrl("/path")          // → "https://example.com/path"
fixUrl("//cdn.x.com/a") // → "https://cdn.x.com/a"
fixUrl("https://...")    // → unchanged
fixUrl(null)             // → null

// Null-safe Jsoup wrappers
document.selectFirstOrNull("css.selector")    // → Element? (never throws)
element.selectFirstOrNull("css.selector")     // → Element?
element.textOrNull()                          // → String? (null if blank)
element.attrOrNull("href")                    // → String? (null if blank)
```

---

## HTML Scraping Pattern

Used when the site renders content server-side (no JavaScript needed).

```kotlin
// Use get(url).document — gives pre-parsed Jsoup Document
val doc = get(url).document

// Common selectors
doc.selectFirstOrNull("meta[property='og:image']")?.attrOrNull("content")  // cover
doc.selectFirstOrNull("h1")?.textOrNull()                                   // title
doc.select("a.chapter-item")                                                 // chapter list
el.ownText()    // text of element itself, excluding child elements
el.text()       // text of element including all children
el.html()       // inner HTML of element

// Cover from CSS background-image
val style = el.attrOrNull("style") ?: ""
val cover = Regex("""url\(([^)]+)\)""").find(style)?.groupValues?.get(1)?.trim()

// LD+JSON metadata (reliable for author/synopsis/genre)
val ldScript = document.select("script[type='application/ld+json']")
    .firstOrNull { it.data().contains("\"@type\":\"Book\"") }?.data()
if (ldScript != null) {
    val json = JSONObject(ldScript)
    val author = json.optJSONObject("author")?.optString("name")
    val synopsis = json.optString("description")
    val genre = json.opt("genre")  // may be String or JSONArray
}
```

---

## REST API Pattern

Used when the site exposes a JSON API (much simpler than scraping).

```kotlin
private val apiUrl = "$mainUrl/api"
private val jsonHeaders = mapOf("Accept" to "application/json")

override suspend fun loadMainPage(...): MainPageResult {
    val url = "$apiUrl/novels?page=$page&limit=24"
    return try {
        val json = JSONObject(get(url, jsonHeaders).text)
        val arr = json.optJSONArray("data") ?: JSONArray()
        val novels = parseNovels(arr)
        val hasNext = json.optJSONObject("meta")?.optBoolean("hasMore", false) ?: false
        MainPageResult(url = url, novels = novels, hasNextPage = hasNext)
    } catch (_: Throwable) { MainPageResult(url = url, novels = emptyList()) }
}

private fun parseNovels(arr: JSONArray): List<Novel> {
    val result = mutableListOf<Novel>()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val title = obj.optString("title", null) ?: continue
        val id = obj.optString("id", null) ?: continue
        val coverRaw = obj.optString("cover", null)
        val cover = coverRaw?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        result.add(Novel(name = title, url = "$mainUrl/novel/$id", posterUrl = cover, apiName = this.name))
    }
    return result
}
```

**RSC (React Server Component) sites** — send header `"rsc" to "1"` to bypass Cloudflare and get wire-format JSON:
```kotlin
private val rscHeaders = mapOf("rsc" to "1", "Accept" to "*/*")
val body = get(fullUrl, rscHeaders).text  // raw RSC wire format, parse with Regex
```

---

## Common Pitfalls & Fixes

### 1. URL encoding: path segments need `%20` not `+`

```kotlin
// WRONG — URLEncoder produces "+" for spaces, breaks path-based URLs
"$mainUrl/tags/${java.net.URLEncoder.encode(genre, "UTF-8")}"

// CORRECT — replace "+" with "%20" after encoding
"$mainUrl/tags/${java.net.URLEncoder.encode(genre, "UTF-8").replace("+", "%20")}"

// Simple names with only spaces
"$mainUrl/tags/${genre.replace(" ", "%20")}"
```

### 2. Always provide defaults for optional filter params

```kotlin
// Bad — crashes if orderBy is null
val sort = orderBy!!

// Good
val sort = orderBy?.takeIf { it.isNotBlank() } ?: "latest"
```

### 3. Resolve relative URLs before storing in Novel/Chapter

```kotlin
// Use fixUrl() or manual check
val novelUrl = if (href.startsWith("http")) href else "$mainUrl$href"
```

### 4. Cover from `og:image` is most reliable

```kotlin
// Try og:image first, fall back to img tag
val cover = document.selectFirstOrNull("meta[property='og:image']")?.attrOrNull("content")
    ?: document.selectFirstOrNull("div.cover img")?.attrOrNull("src")
        ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
```

### 5. `textOrNull()` vs `ownText()`

```kotlin
// element.textOrNull() = element.text() — includes ALL descendant text
// Use element.ownText() when element has child elements you want to skip
// e.g. <span class="badge">NEW</span> Chapter 5 — ownText() gives "Chapter 5"
val chTitle = a.ownText().trim().takeIf { it.isNotBlank() } ?: a.textOrNull()
```

### 6. Null safety with `mapNotNull`

```kotlin
// Return null from mapNotNull lambda to skip that element
document.select("li.chapter").mapNotNull { li ->
    val href = li.selectFirstOrNull("a")?.attrOrNull("href") ?: return@mapNotNull null
    val name = li.selectFirstOrNull("span.title")?.textOrNull() ?: return@mapNotNull null
    Chapter(name = name, url = "$mainUrl$href")
}
```

### 7. Chapter list CSS-hidden in HTML (still parseable)

Some sites render chapters server-side but hide them with CSS (`display:none`). Jsoup parses DOM, ignores CSS — just use the selector normally.

### 8. Genres — filter out navigation labels

Some sites include navigation categories in the genre field:
```kotlin
val excludeLabels = setOf("browse", "latest novels", "completed novels", "all")
val genres = rawGenreString.split(",")
    .map { it.trim().lowercase() }
    .filter { it.isNotBlank() && it !in excludeLabels }
    .distinctBy { it }
```

### 9. `window.__DATA__` JavaScript variable extraction

```kotlin
private fun extractWindowData(document: Document): JSONObject? {
    val script = document.select("script").find { it.data().contains("window.__DATA__") }
        ?.data() ?: return null
    val jsonStr = Regex("""window\.__DATA__\s*=\s*(\{.+?\})\s*;?\s*\n""", RegexOption.DOT_MATCHES_ALL)
        .find(script)?.groupValues?.get(1) ?: return null
    return try { JSONObject(jsonStr) } catch (_: Throwable) { null }
}
```

---

## Binary / EPUB Download Pattern

Use only when `NetworkClient.get()` is insufficient (binary response like EPUB/ZIP). Requires `OkHttpClient` directly — available because `okhttp3` is an `implementation` dep in sources.

```kotlin
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val httpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
}

override suspend fun loadChapterContent(url: String): String? {
    val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
    return withContext(Dispatchers.IO) { downloadAndParseEpub(fullUrl) }
}

private fun downloadAndParseEpub(url: String): String? {
    val request = Request.Builder()
        .url(url)
        .header("Referer", url.replace("/bibi-bookshelf/", "/read/"))
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .build()
    val response = httpClient.newCall(request).execute()
    if (!response.isSuccessful) return null
    val bytes = response.body?.bytes() ?: return null
    return parseEpub(bytes)
}

private fun parseEpub(bytes: ByteArray): String? {
    // 1. Read all ZIP entries into memory
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    // 2. Find OPF via META-INF/container.xml
    val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8) ?: return null
    val opfPath = Regex("""full-path="([^"]+\.opf)"""").find(containerXml)?.groupValues?.get(1) ?: return null
    val opfText = entries[opfPath]?.toString(Charsets.UTF_8) ?: return null
    val opfDir = opfPath.substringBeforeLast('/', "")
    // 3. Parse manifest and spine
    val manifest = mutableMapOf<String, String>()
    Regex("""<item\b[^>]+\bid="([^"]+)"[^>]+\bhref="([^"#]+)"""").findAll(opfText)
        .forEach { manifest[it.groupValues[1]] = it.groupValues[2] }
    val spineIds = Regex("""<itemref\b[^>]+\bidref="([^"]+)"""")
        .findAll(opfText).map { it.groupValues[1] }.toList()
    // 4. Combine content XHTML bodies
    val skipPatterns = listOf("cover", "titlepage", "toc", "copyright", "nav")
    val sb = StringBuilder()
    for (id in spineIds) {
        val href = manifest[id] ?: continue
        if (skipPatterns.any { href.lowercase().contains(it) }) continue
        val entryPath = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
        val xhtml = (entries[entryPath] ?: entries[href])?.toString(Charsets.UTF_8) ?: continue
        val body = Regex("""<body[^>]*>(.*?)</body>""", RegexOption.DOT_MATCHES_ALL)
            .find(xhtml)?.groupValues?.get(1) ?: continue
        sb.append(body.trim()).append("\n")
    }
    return sb.toString().trim().takeIf { it.isNotBlank() }
}
```

---

## Testing

### File location
`sources/src/test/kotlin/com/kmhmubin/kothagolp/sources/SourceEvaluationTest.kt`

### Run all tests (no network)
```bash
./gradlew :sources:testDebugUnitTest
```

### Run network tests for one provider
```bash
RUN_SOURCE_TESTS=true ./gradlew :sources:testDebugUnitTest \
  --tests "*.SourceEvaluationTest.testExampleProvider"
```

### Run all provider network tests
```bash
RUN_SOURCE_TESTS=true ./gradlew :sources:testDebugUnitTest \
  --tests "*.SourceEvaluationTest.testAllProviders"
```

### Build APK
```bash
./gradlew :sources:assembleDebug
# Output: sources/build/outputs/apk/debug/sources-debug.apk
```

### Adding a test for a new provider
```kotlin
// In SourceEvaluationTest.kt — add individual test
@Test
fun testExampleProvider() = skipIfNoNetwork {
    evaluateProvider(ExampleProvider(), "fantasy")  // pick a query that returns results
}

// Add to testAllProviders list
val providers = listOf(
    ...,
    ExampleProvider()
)

// Update testProvidersInstantiate count
assert(providers.size == N) { "Expected N providers, got ${providers.size}" }
```

### What `evaluateProvider` checks
1. `loadMainPage(1)` → `novels.isNotEmpty()`
2. `search(query)` → `results.isNotEmpty()`
3. `load(firstResult.url)` → not null, `chapters.isNotEmpty()`
4. `loadChapterContent(firstChapter.url)` → not null, not blank

---

## Adding a New Provider Checklist

```
[ ] Research site structure (HTML vs JSON API, CF protection, chapter URL format)
[ ] git checkout -b add-{providername}
[ ] Create XxxProvider.kt in sources/src/main/java/.../sources/
[ ] Set name, mainUrl, iconUrl, hasMainPage
[ ] Define orderBys (sort options) and tags (genre filters)
[ ] Implement loadMainPage — test pagination works
[ ] Implement search — URL-encode query with .replace("+", "%20") if in path
[ ] Implement load — chapters list MUST be non-empty
[ ] Implement loadChapterContent — return HTML string
[ ] Add individual @Test in SourceEvaluationTest.kt
[ ] Add to testAllProviders list and testProvidersInstantiate count
[ ] Run: RUN_SOURCE_TESTS=true ./gradlew :sources:testDebugUnitTest --tests "*.testXxxProvider"
[ ] Run: ./gradlew :sources:assembleDebug
[ ] git add + commit (feat: add Xxx source provider)
[ ] git checkout main && git merge --no-ff add-{name} -m "feat: ... [skip ci]"
[ ] git push origin main
```

---

## Provider Reference Table

| Provider | Site | Approach | Notes |
|----------|------|----------|-------|
| `AllNovelProvider` | allnovel.org | HTML scraping | Standard scraper |
| `FenrirRealmProvider` | fenrirrealm.com | HTML scraping | Standard scraper |
| `FreeWebNovelProvider` | freewebnovel.com | HTML scraping | Standard scraper |
| `LibReadProvider` | libread.com | HTML scraping | Standard scraper |
| `LightNovelTranslationsProvider` | lightnoveltranslations.com | HTML scraping | Standard scraper |
| `LightNovelWorldProvider` | lightnovelworld.co | HTML scraping | Standard scraper |
| `LnoriProvider` | lnori.com | HTML scraping | Standard scraper |
| `NovelBinProvider` | novelbin.me | HTML scraping | Standard scraper |
| `NovelBuddyProvider` | novelbuddy.io | REST JSON API | `api.novelbuddy.io`, `__NEXT_DATA__` for detail |
| `NovelDexProvider` | noveldex.io | REST JSON API + RSC | `/api/series` for browse; RSC headers for detail; XOR-encrypted chapter content |
| `NovelFireProvider` | novelfire.net | HTML scraping | Standard scraper |
| `NovelsOnlineProvider` | novelsonline.net | HTML scraping | Standard scraper |
| `PawReadProvider` | pawread.com | HTML scraping | `og:image` cover, JSON-LD metadata, onclick chapter IDs |
| `RanobesProvider` | ranobes.net | HTML + `window.__DATA__` | Chapter list from JS variable; genre URLs use `%20` not `+` |
| `RoyalRoadProvider` | royalroad.com | HTML scraping | Standard scraper |
| `WebnovelProvider` | webnovel.com | HTML scraping | Standard scraper |
| `CyrisiaProvider` | cyrisia.com | REST JSON API + EPUB binary | `/api/bookshelf` browse; EPUB download via OkHttpClient + Referer; ZipInputStream + OPF spine parsing |
| `FuckNovelPiaProvider` | fucknovelpia.com | HTML scraping | CSS-hidden chapter list (`ul.chapter-list li`); LD+JSON for metadata |
| `NovelArchiveProvider` | novelarchive.cc | REST JSON API | Full API at `/api/novels`; plain-text chapter content converted to `<p>` HTML |

---

## Quick Reference: Site Investigation Commands

```bash
# Test if site is CF-protected
curl -s -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" "https://example.com/" | head -20

# Find novel card structure
curl -s -A "Mozilla/5.0" "https://example.com/browse" | python3 -c "
import sys, re
content = sys.stdin.read()
card = re.search(r'<article[^>]*>(.*?)</article>', content, re.DOTALL)
print(card.group(0)[:500] if card else 'No article found')
"

# Find chapter links on novel detail page
curl -s -A "Mozilla/5.0" "https://example.com/novel/slug" | python3 -c "
import sys, re
content = sys.stdin.read()
links = re.findall(r'href=\"([^\"]+chapter[^\"]+)\"', content)
print(links[:5])
"

# Check if it's a JSON API
curl -s -A "Mozilla/5.0" -H "Accept: application/json" "https://example.com/api/novels" | python3 -m json.tool | head -30

# Try RSC bypass for Next.js sites
curl -s -A "Mozilla/5.0" -H "rsc: 1" "https://example.com/novel/slug" | head -200
```
