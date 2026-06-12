package com.kmhmubin.kothagolp.sources

import com.kmhmubin.kothagolp.domain.model.MainPageResult
import com.kmhmubin.kothagolp.domain.model.Novel
import com.kmhmubin.kothagolp.domain.model.NovelDetails
import com.kmhmubin.kothagolp.provider.MainProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Source evaluation tests.
 * These are integration tests that make real HTTP requests.
 * Run selectively: ./gradlew :sources:test --tests "*.SourceEvaluationTest.*"
 *
 * Each test checks basic contract:
 * - loadMainPage returns at least 1 novel
 * - search returns results for a common query
 * - load returns valid NovelDetails with chapters
 * - loadChapterContent returns non-empty HTML
 */
class SourceEvaluationTest {

    // Set to true to run real network tests (disabled by default for CI)
    private val runNetworkTests = System.getenv("RUN_SOURCE_TESTS") == "true"

    // Providers requiring Cloudflare managed-challenge bypass — work in app (WebView), not JVM
    private val cloudflareProtected = setOf("EmpireNovel", "WuxiaBox")

    private fun skipIfNoNetwork(block: () -> Unit) {
        if (!runNetworkTests) {
            println("Skipping network test (set RUN_SOURCE_TESTS=true to enable)")
            return
        }
        block()
    }

    private fun skipIfCloudflare(provider: MainProvider, block: () -> Unit) {
        if (provider.name in cloudflareProtected) {
            println("  SKIP: ${provider.name} — Cloudflare managed challenge (works in app, not JVM tests)")
            return
        }
        block()
    }

    private fun evaluateProvider(provider: MainProvider, searchQuery: String) {
        println("\n=== Evaluating: ${provider.name} ===")

        if (provider.hasMainPage) {
            val mainPage: MainPageResult = runBlocking { provider.loadMainPage(1) }
            println("  Main page: ${mainPage.novels.size} novels")
            assert(mainPage.novels.isNotEmpty()) { "${provider.name}: loadMainPage returned empty list" }
        }

        val searchResults: List<Novel> = runBlocking { provider.search(searchQuery) }
        println("  Search ('$searchQuery'): ${searchResults.size} results")
        assert(searchResults.isNotEmpty()) { "${provider.name}: search('$searchQuery') returned empty list" }

        val firstResult = searchResults.first()
        println("  First result: ${firstResult.name} — ${firstResult.url}")

        val details: NovelDetails? = runBlocking { provider.load(firstResult.url) }
        println("  Novel details: ${details?.name ?: "null"}, ${details?.chapters?.size ?: 0} chapters")
        assert(details != null) { "${provider.name}: load() returned null for ${firstResult.url}" }
        assert(details!!.chapters.isNotEmpty()) { "${provider.name}: load() returned no chapters" }

        val firstChapter = details.chapters.first()
        val content: String? = runBlocking { provider.loadChapterContent(firstChapter.url) }
        println("  Chapter content: ${content?.length ?: 0} chars")
        assert(!content.isNullOrBlank()) { "${provider.name}: loadChapterContent() returned blank for ${firstChapter.url}" }

        println("  PASS: ${provider.name}")
    }

    @Test
    fun testAllNovelProvider() = skipIfNoNetwork {
        evaluateProvider(AllNovelProvider(), "cultivation")
    }

    @Test
    fun testNovelBinProvider() = skipIfNoNetwork {
        evaluateProvider(NovelBinProvider(), "system")
    }

    @Test
    fun testLibReadProvider() = skipIfNoNetwork {
        evaluateProvider(LibReadProvider(), "reincarnation")
    }

    @Test
    fun testFreeWebNovelProvider() = skipIfNoNetwork {
        evaluateProvider(FreeWebNovelProvider(), "fantasy")
    }

    @Test
    fun testNovelFireProvider() = skipIfNoNetwork {
        evaluateProvider(NovelFireProvider(), "dragon")
    }

    @Test
    fun testNovelsOnlineProvider() = skipIfNoNetwork {
        evaluateProvider(NovelsOnlineProvider(), "warrior")
    }

    @Test
    fun testLnoriProvider() = skipIfNoNetwork {
        evaluateProvider(LnoriProvider(), "magic")
    }

    @Test
    fun testEmpireNovelProvider() = skipIfNoNetwork {
        val provider = EmpireNovelProvider()
        skipIfCloudflare(provider) { evaluateProvider(provider, "sword") }
    }

    @Test
    fun testRoyalRoadProvider() = skipIfNoNetwork {
        evaluateProvider(RoyalRoadProvider(), "dungeon")
    }

    @Test
    fun testWebnovelProvider() = skipIfNoNetwork {
        evaluateProvider(WebnovelProvider(), "martial")
    }

    @Test
    fun testLightNovelWorldProvider() = skipIfNoNetwork {
        evaluateProvider(LightNovelWorldProvider(), "isekai")
    }

    @Test
    fun testScribblehubProvider() = skipIfNoNetwork {
        evaluateProvider(ScribblehubProvider(), "harem")
    }

    @Test
    fun testReadNovelFullProvider() = skipIfNoNetwork {
        evaluateProvider(ReadNovelFullProvider(), "cultivation")
    }

    @Test
    fun testWuxiaBoxProvider() = skipIfNoNetwork {
        val provider = WuxiaBoxProvider()
        skipIfCloudflare(provider) { evaluateProvider(provider, "wuxia") }
    }

    @Test
    fun testWtrLabProvider() = skipIfNoNetwork {
        evaluateProvider(WtrLabProvider(), "system")
    }

    @Test
    fun testAllProviders() = skipIfNoNetwork {
        val providers = listOf(
            AllNovelProvider(), NovelBinProvider(), LibReadProvider(),
            FreeWebNovelProvider(), NovelFireProvider(), NovelsOnlineProvider(),
            LnoriProvider(), EmpireNovelProvider(), RoyalRoadProvider(),
            WebnovelProvider(), LightNovelWorldProvider(), ScribblehubProvider(),
            ReadNovelFullProvider(), WuxiaBoxProvider(), WtrLabProvider()
        )
        val errors = mutableListOf<Pair<String, String>>()
        for (provider in providers) {
            if (provider.name in cloudflareProtected) {
                println("  SKIP: ${provider.name} — Cloudflare managed challenge")
                continue
            }
            try {
                evaluateProvider(provider, "fantasy")
            } catch (e: Exception) {
                errors.add(provider.name to (e.message ?: e.toString()))
                System.err.println("FAIL: ${provider.name} — ${e.message}")
            }
        }
        if (errors.isNotEmpty()) {
            val summary = errors.joinToString("\n") { "  ${it.first}: ${it.second}" }
            assert(false) { "Failed providers:\n$summary" }
        }
    }

    @Test
    fun testProvidersInstantiate() {
        // Smoke test — just verify all 15 providers can be instantiated
        val providers = listOf(
            AllNovelProvider(), NovelBinProvider(), LibReadProvider(),
            FreeWebNovelProvider(), NovelFireProvider(), NovelsOnlineProvider(),
            LnoriProvider(), EmpireNovelProvider(), RoyalRoadProvider(),
            WebnovelProvider(), LightNovelWorldProvider(), ScribblehubProvider(),
            ReadNovelFullProvider(), WuxiaBoxProvider(), WtrLabProvider()
        )
        assert(providers.size == 15) { "Expected 15 providers, got ${providers.size}" }
        providers.forEach { provider ->
            assert(provider.name.isNotBlank()) { "Provider has blank name: $provider" }
            assert(provider.mainUrl.startsWith("https://")) { "${provider.name}: mainUrl should start with https://" }
        }
        println("All 15 providers instantiate correctly.")
    }
}
