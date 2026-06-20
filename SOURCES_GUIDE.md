# Kothagolp Sources — Developer Guide

This document explains everything needed to build new sources or update existing ones in the `kothagolp-sources` repository. Read it fully before writing any code.

---

## What This Repo Does

This repo contains all novel source (provider) implementations for the Kothagolp app. It builds into a single `sources.apk` file. The main Kothagolp app downloads this APK at runtime and loads all providers dynamically via `DexClassLoader` — no app update needed when sources change.

**Flow:**
```
You push to main
    → CI builds sources.apk
    → CI bumps version in manifest.json and commits it back
    → CI publishes sources.apk to GitHub Releases
    → Kothagolp app fetches manifest.json on next launch
    → App compares version, downloads new sources.apk if newer
    → App loads all 35+ providers via reflection
```

---

## Repository Structure

```
kothagolp-sources/
├── manifest.json                          ← Source index (version + class list)
├── source-api/                            ← Abstract contract (DO NOT EDIT)
│   └── src/main/kotlin/com/kmhmubin/kothagolp/
│       ├── provider/MainProvider.kt       ← Base class all sources extend
│       └── domain/model/                  ← Data classes
│           ├── Novel.kt
│           ├── NovelDetails.kt
│           ├── Chapter.kt
│           ├── MainPageResult.kt
│           ├── FilterOption.kt
│           └── FilterGroup.kt
└── sources/                               ← All provider implementations
    └── src/main/java/com/kmhmubin/kothagolp/sources/
        ├── NovelFireProvider.kt
        ├── RoyalRoadProvider.kt
        └── ... (35 total)
```

---

## The Contract — MainProvider

Every source is a Kotlin class that extends `MainProvider`. Package must be `com.kmhmubin.kothagolp.sources`.

```kotlin
package com.kmhmubin.kothagolp.sources

class MySourceProvider : MainProvider() {
    // ... implement everything below
}
```

### Required Properties

```kotlin
abstract val name: String        // Display name shown in app. e.g. "Royal Road"
abstract val mainUrl: String     // Root URL of the site. e.g. "https://royalroad.com"
```

### Optional Properties (override as needed)

```kotlin
open val hasMainPage: Boolean = true          // false if site has no browse/discovery page
open val iconUrl: String? = null              // URL to source icon (favicon works)
open val rateLimitTime: Long = 0L             // Milliseconds to wait between requests
open val tags: List<FilterOption> = emptyList()         // Genre/tag filter options
open val orderBys: List<FilterOption> = emptyList()     // Sort order options
open val extraFilterGroups: List<FilterGroup> = emptyList()  // Additional filter groups
open val hasReviews: Boolean = false          // true if site has reviews
```

### Required Methods (must implement all 4)

```kotlin
// Browse/discovery page — returns a paginated list of novels
abstract suspend fun loadMainPage(
    page: Int,
    orderBy: String? = null,        // value from orderBys list, null = default
    tag: String? = null,            // value from tags list, null = all genres
    extraFilters: Map<String, String> = emptyMap()  // keys from extraFilterGroups
): MainPageResult

// Search by title — returns list of matching novels
abstract suspend fun search(query: String): List<Novel>

// Load full novel details + chapter list from a novel URL
abstract suspend fun load(url: String): NovelDetails?

// Load the actual text content of a chapter — returns HTML string
abstract suspend fun loadChapterContent(url: String): String?
```

### Optional Methods

```kotlin
// Only override if hasReviews = true
open suspend fun loadReviews(url: String, page: Int, showSpoilers: Boolean = false): List<Any> = emptyList()
```

---

## Data Classes

### Novel — used in search results and browse lists

```kotlin
data class Novel(
    val name: String,           // Title of the novel
    val url: String,            // Full URL to the novel's detail page
    val posterUrl: String? = null,      // Cover image URL (absolute)
    val rating: Int? = null,            // Rating 0–100 (percentage scale)
    val latestChapter: String? = null,  // Name/number of latest chapter (display only)
    val apiName: String = ""            // Set automatically — leave as default ""
)
```

### NovelDetails — full metadata returned by `load()`

```kotlin
data class NovelDetails(
    val url: String,                    // Same URL passed to load()
    val name: String,                   // Novel title
    val chapters: List<Chapter>,        // All chapters, ordered oldest → newest
    val author: String? = null,
    val posterUrl: String? = null,
    val synopsis: String? = null,       // Plain text or HTML synopsis
    val tags: List<String>? = null,     // Genre tags as strings
    val rating: Int? = null,            // 0–100
    val peopleVoted: Int? = null,
    val status: String? = null,         // "Ongoing", "Completed", "Hiatus"
    val views: Int? = null,
    val relatedNovels: List<Novel>? = null
)
```

### Chapter — one entry in the chapter list

```kotlin
data class Chapter(
    val name: String,               // Display name, e.g. "Chapter 1 — The Beginning"
    val url: String,                // Full URL to the chapter page
    val dateOfRelease: String? = null   // Any date string, for display only
)
```

### MainPageResult — returned by `loadMainPage()`

```kotlin
data class MainPageResult(
    val url: String,                // The page URL that was fetched
    val novels: List<Novel>,        // Novels on this page
    val hasNextPage: Boolean = true // false when on last page
)
```

### FilterOption — one item in a dropdown filter

```kotlin
data class FilterOption(
    val label: String,   // What the user sees, e.g. "Action"
    val value: String    // What gets sent to the site, e.g. "action" or "3"
)
```

### FilterGroup — a named group of filter options (for extra filters)

```kotlin
data class FilterGroup(
    val key: String,                    // Unique key, passed in extraFilters map
    val label: String,                  // Label shown to user
    val options: List<FilterOption>,
    val defaultValue: String? = null
)
```

---

## Built-in Helper Methods (from MainProvider)

These are available inside any provider without imports:

```kotlin
// HTTP — all return NetworkResponse with .document (Jsoup), .text, .isSuccessful, .code
protected suspend fun get(url: String, headers: Map<String, String> = emptyMap()): NetworkResponse
protected suspend fun post(url: String, data: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): NetworkResponse
protected suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): NetworkResponse

// URL normalization — handles relative, protocol-relative, absolute URLs
protected fun fixUrl(url: String?): String?
// e.g. fixUrl("/novel/123") → "https://yoursite.com/novel/123"
// e.g. fixUrl("//cdn.example.com/img.jpg") → "https://cdn.example.com/img.jpg"
// e.g. fixUrl("https://...") → unchanged

// Jsoup helpers — null-safe CSS selectors
protected fun Document.selectFirstOrNull(cssQuery: String): Element?
protected fun Element.selectFirstOrNull(cssQuery: String): Element?
protected fun Element.textOrNull(): String?           // null if blank
protected fun Element.attrOrNull(key: String): String? // null if blank
```

### NetworkResponse fields

```kotlin
response.document      // Jsoup Document — use for HTML parsing
response.text          // Raw response body as String
response.isSuccessful  // true if HTTP 2xx
response.code          // HTTP status code
response.headers       // Map<String, String> of response headers
```

---

## Available Libraries

In your provider you can use:

| Library | What for |
|---------|----------|
| `org.jsoup` | HTML parsing — `Document`, `Element`, CSS selectors |
| `okhttp3` | HTTP (already handled by `get()`/`post()` — rarely need directly) |
| `org.json` | JSON parsing — `JSONObject`, `JSONArray` |
| `kotlinx.coroutines` | `withContext`, `async`, `delay` etc |

Standard Java library is also fully available.

---

## How to Write a New Source

### Step 1 — Inspect the target site

Before writing any code:
- Find the browse/listing URL (e.g. `https://site.com/novels?page=1&genre=action&sort=latest`)
- Find the search URL (e.g. `https://site.com/search?q=query`)
- Find a novel detail page and note the CSS selectors for title, cover, author, synopsis, chapters
- Find a chapter page and note the CSS selector for the content area
- Check if the site uses an API (JSON responses) or pure HTML

### Step 2 — Create the file

Create `sources/src/main/java/com/kmhmubin/kothagolp/sources/YourSiteProvider.kt`

```kotlin
package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.Chapter
import com.kmhmubin.kothagolp.domain.model.FilterOption
import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider

class YourSiteProvider : MainProvider() {

    override val name = "Your Site"
    override val mainUrl = "https://yoursite.com"
    override val iconUrl = "https://www.google.com/s2/favicons?domain=yoursite.com&sz=64"
    override val hasMainPage = true

    override val tags = listOf(
        FilterOption("All", ""),
        FilterOption("Action", "action"),
        // ... add all genres the site supports
    )

    override val orderBys = listOf(
        FilterOption("Latest", "latest"),
        FilterOption("Popular", "popular"),
        // ... add sort options
    )

    override suspend fun loadMainPage(
        page: Int,
        orderBy: String?,
        tag: String?,
        extraFilters: Map<String, String>
    ): MainPageResult {
        val url = buildString {
            append("$mainUrl/novels?page=$page")
            if (!orderBy.isNullOrBlank()) append("&sort=$orderBy")
            if (!tag.isNullOrBlank()) append("&genre=$tag")
        }
        val doc = get(url).document
        val novels = doc.select(".novel-item").mapNotNull { el ->
            val titleEl = el.selectFirstOrNull("a.title") ?: return@mapNotNull null
            Novel(
                name = titleEl.text(),
                url = fixUrl(titleEl.attr("href")) ?: return@mapNotNull null,
                posterUrl = fixUrl(el.selectFirstOrNull("img")?.attr("src")),
            )
        }
        val hasNext = doc.selectFirstOrNull(".pagination .next") != null
        return MainPageResult(url = url, novels = novels, hasNextPage = hasNext)
    }

    override suspend fun search(query: String): List<Novel> {
        val url = "$mainUrl/search?q=${query.trim().replace(" ", "+")}"
        val doc = get(url).document
        return doc.select(".novel-item").mapNotNull { el ->
            val titleEl = el.selectFirstOrNull("a.title") ?: return@mapNotNull null
            Novel(
                name = titleEl.text(),
                url = fixUrl(titleEl.attr("href")) ?: return@mapNotNull null,
                posterUrl = fixUrl(el.selectFirstOrNull("img")?.attr("src")),
            )
        }
    }

    override suspend fun load(url: String): NovelDetails? {
        val doc = get(url).document
        val title = doc.selectFirstOrNull("h1.novel-title")?.text() ?: return null
        val chapters = doc.select(".chapter-list a").map { el ->
            Chapter(
                name = el.text(),
                url = fixUrl(el.attr("href")) ?: el.attr("href"),
                dateOfRelease = el.selectFirstOrNull(".date")?.text()
            )
        }
        return NovelDetails(
            url = url,
            name = title,
            chapters = chapters,
            author = doc.selectFirstOrNull(".author span")?.text(),
            posterUrl = fixUrl(doc.selectFirstOrNull(".cover img")?.attr("src")),
            synopsis = doc.selectFirstOrNull(".description")?.text(),
            tags = doc.select(".genres a").map { it.text() },
            status = doc.selectFirstOrNull(".status span")?.text()
        )
    }

    override suspend fun loadChapterContent(url: String): String? {
        val doc = get(url).document
        return doc.selectFirstOrNull("#chapter-content")?.html()
    }
}
```

### Step 3 — Register in manifest.json

Add a new entry to the `sources` array in `manifest.json` at the root of the repo:

```json
{
  "id": "yoursite",
  "class": "com.kmhmubin.kothagolp.sources.YourSiteProvider"
}
```

Rules for `id`:
- Lowercase, no spaces, no special characters except hyphens
- Must be unique across all sources
- Should match the domain name (e.g. `royalroad`, `novelfire`, `wuxiaworld`)

### Step 4 — Build and test

```bash
# From repo root
./gradlew :sources:assembleRelease

# Run tests (requires network)
RUN_SOURCE_TESTS=true ./gradlew :sources:test
```

### Step 5 — Push

Push to `main`. CI will automatically:
1. Build `sources.apk`
2. Bump `manifest.json` version
3. Publish GitHub Release
4. Commit updated `manifest.json` back

---

## Common Patterns

### Handling lazy-loaded chapters (AJAX)

Some sites load chapter lists via a separate API call:

```kotlin
override suspend fun load(url: String): NovelDetails? {
    val doc = get(url).document
    val novelId = doc.selectFirstOrNull("meta[data-id]")?.attr("data-id") ?: return null

    // Fetch chapter list from API
    val chaptersResponse = get("$mainUrl/api/novel/$novelId/chapters")
    val json = org.json.JSONArray(chaptersResponse.text)
    val chapters = (0 until json.length()).map { i ->
        val obj = json.getJSONObject(i)
        Chapter(
            name = obj.getString("title"),
            url = fixUrl(obj.getString("url")) ?: obj.getString("url")
        )
    }
    // ...
}
```

### Sites with pagination inside chapter list

```kotlin
suspend fun fetchAllChapters(novelId: String): List<Chapter> {
    val all = mutableListOf<Chapter>()
    var page = 1
    while (true) {
        val response = get("$mainUrl/ajax/chapters?id=$novelId&page=$page")
        val items = parseChapters(response.document)
        if (items.isEmpty()) break
        all.addAll(items)
        page++
    }
    return all
}
```

### Chapters ordered newest-first on site

The app always displays chapters oldest-first. If the site returns newest-first, reverse the list:

```kotlin
chapters = parsedChapters.reversed()
```

### Cover images with lazy loading

Many sites use `data-src` instead of `src` for lazy-loaded images:

```kotlin
val posterUrl = fixUrl(
    imgEl?.attr("data-src").takeIf { !it.isNullOrBlank() }
        ?: imgEl?.attr("src")
)
```

### Rate limiting

If a site blocks aggressive scraping:

```kotlin
override val rateLimitTime: Long = 500L  // 500ms between requests
```

The app respects this automatically.

### Custom headers

```kotlin
val response = get(url, headers = mapOf(
    "Referer" to mainUrl,
    "X-Requested-With" to "XMLHttpRequest"
))
```

### JSON API sources

When the site has a proper REST API instead of HTML:

```kotlin
override suspend fun search(query: String): List<Novel> {
    val response = get("$mainUrl/api/search?keyword=$query")
    val json = org.json.JSONObject(response.text)
    val results = json.getJSONArray("data")
    return (0 until results.length()).mapNotNull { i ->
        val obj = results.getJSONObject(i)
        Novel(
            name = obj.getString("title"),
            url = "$mainUrl/novel/${obj.getString("slug")}",
            posterUrl = obj.optString("cover").takeIf { it.isNotBlank() }
        )
    }
}
```

### Chapter content cleanup

Strip unwanted elements before returning content:

```kotlin
override suspend fun loadChapterContent(url: String): String? {
    val doc = get(url).document
    val content = doc.selectFirstOrNull("#chapter-content") ?: return null
    // Remove ads, navigation links, etc.
    content.select(".ads, .chapter-nav, script, .donation-box").remove()
    return content.html()
}
```

---

## Updating an Existing Source

1. Find the provider file in `sources/src/main/java/com/kmhmubin/kothagolp/sources/`
2. Make your changes — fix selectors, update URLs, add new filters, etc.
3. **Do not change the class name** — the class name is what the app uses to load it via reflection
4. **Do not change the `id` in manifest.json** — changing the id means the app treats it as a new source
5. Push to `main` — CI handles the rest

---

## Checklist Before Pushing a New Source

- [ ] Class is in package `com.kmhmubin.kothagolp.sources`
- [ ] Class name ends with `Provider` and matches the filename
- [ ] `name` property is human-readable (shown in app UI)
- [ ] `mainUrl` has no trailing slash
- [ ] `loadMainPage()` handles `page=1` correctly (first page)
- [ ] `loadMainPage()` sets `hasNextPage = false` on the last page
- [ ] Chapter list in `load()` is ordered **oldest first**
- [ ] All URLs returned are absolute (use `fixUrl()` on everything)
- [ ] `loadChapterContent()` returns HTML content string, not full page HTML
- [ ] Entry added to `manifest.json` with unique lowercase `id`
- [ ] `null` is returned (not exception) when a page fails to parse
- [ ] Tested locally: `./gradlew :sources:assembleRelease` succeeds

---

## What NOT to Do

- **Do not** add Android-specific code (Activities, Fragments, Views) — providers are pure data/network logic
- **Do not** use `runBlocking` — all methods are already `suspend`, use `withContext` if needed
- **Do not** store state in instance variables — providers may be re-instantiated at any time
- **Do not** hardcode cookie values — use the `get()` helper which handles cookies automatically
- **Do not** throw exceptions from public methods — return `null` or empty list instead
- **Do not** change `source-api/` — it is the shared contract between this repo and the main app
- **Do not** add dependencies to `sources/build.gradle.kts` without checking the main app supports them — the APK is loaded at runtime, so only standard Android + the listed dependencies are available

---

## Dependency Reference

Available in provider code:

```
org.jsoup.Jsoup
org.jsoup.nodes.Document
org.jsoup.nodes.Element
org.jsoup.select.Elements
org.json.JSONObject
org.json.JSONArray
kotlinx.coroutines.* (Dispatchers, withContext, async, etc.)
okhttp3.* (if needed directly)
java.* (full standard library)
android.util.Log (for debug logging)
```

---

## Project Info

- **Repo:** `github.com/kmhmubin/kothagolp-sources`
- **Manifest URL:** `https://raw.githubusercontent.com/kmhmubin/kothagolp-sources/main/manifest.json`
- **APK URL:** `https://github.com/kmhmubin/kothagolp-sources/releases/latest/download/sources.apk`
- **Build command:** `./gradlew :sources:assembleRelease`
- **Package:** `com.kmhmubin.kothagolp.sources`
- **Min SDK:** 26 (Android 8.0)
- **Language:** Kotlin
