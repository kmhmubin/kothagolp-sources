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

    private val runNetworkTests = System.getenv("RUN_SOURCE_TESTS") == "true"

    private val cloudflareProtected = setOf("EmpireNovel", "WuxiaBox", "GrayCity", "SonicMTL", "WuxiaWorld")

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

    // ======================== Original 15 Providers ========================

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

    // ======================== New Providers ========================

    @Test
    fun testChrysanthemumGardenProvider() = skipIfNoNetwork {
        evaluateProvider(ChrysanthemumGardenProvider(), "cultivation")
    }

    @Test
    fun testFenrirRealmProvider() = skipIfNoNetwork {
        evaluateProvider(FenrirRealmProvider(), "fantasy")
    }

    @Test
    fun testNovelBuddyProvider() = skipIfNoNetwork {
        evaluateProvider(NovelBuddyProvider(), "system")
    }

    @Test
    fun testLightNovelTranslationsProvider() = skipIfNoNetwork {
        evaluateProvider(LightNovelTranslationsProvider(), "romance")
    }

    @Test
    fun testLnMTLProvider() = skipIfNoNetwork {
        evaluateProvider(LnMTLProvider(), "martial")
    }

    @Test
    fun testMTLNovelProvider() = skipIfNoNetwork {
        evaluateProvider(MTLNovelProvider(), "dragon")
    }

    @Test
    fun testPawReadProvider() = skipIfNoNetwork {
        evaluateProvider(PawReadProvider(), "fantasy")
    }

    @Test
    fun testRanobesProvider() = skipIfNoNetwork {
        evaluateProvider(RanobesProvider(), "system")
    }

    @Test
    fun testWattpadProvider() = skipIfNoNetwork {
        evaluateProvider(WattpadProvider(), "romance")
    }

    @Test
    fun testNovelFullProvider() = skipIfNoNetwork {
        evaluateProvider(NovelFullProvider(), "cultivation")
    }

    @Test
    fun testWuxiaWorldProvider() = skipIfNoNetwork {
        val provider = WuxiaWorldProvider()
        skipIfCloudflare(provider) { evaluateProvider(provider, "wuxia") }
    }

    @Test
    fun testWuxiaClickProvider() = skipIfNoNetwork {
        evaluateProvider(WuxiaClickProvider(), "martial arts")
    }

    @Test
    fun testSonicMTLProvider() = skipIfNoNetwork {
        val provider = SonicMTLProvider()
        skipIfCloudflare(provider) { evaluateProvider(provider, "fantasy") }
    }

    @Test
    fun testHiraethTranslationProvider() = skipIfNoNetwork {
        evaluateProvider(HiraethTranslationProvider(), "romance")
    }

    @Test
    fun testNovelDexProvider() = skipIfNoNetwork {
        evaluateProvider(NovelDexProvider(), "fantasy")
    }

    @Test
    fun testNovelLightProvider() = skipIfNoNetwork {
        evaluateProvider(NovelLightProvider(), "fantasy")
    }

    @Test
    fun testGrayCityProvider() = skipIfNoNetwork {
        val provider = GrayCityProvider()
        skipIfCloudflare(provider) { evaluateProvider(provider, "action") }
    }

    @Test
    fun testNoBadNovelProvider() = skipIfNoNetwork {
        evaluateProvider(NoBadNovelProvider(), "fantasy")
    }

    @Test
    fun testReadHiveProvider() = skipIfNoNetwork {
        evaluateProvider(ReadHiveProvider(), "romance")
    }

    @Test
    fun testOceanOfPDFProvider() = skipIfNoNetwork {
        evaluateProvider(OceanOfPDFProvider(), "fantasy")
    }

    // ======================== Bulk Tests ========================

    @Test
    fun testAllProviders() = skipIfNoNetwork {
        val providers = listOf(
            AllNovelProvider(), NovelBinProvider(), LibReadProvider(),
            FreeWebNovelProvider(), NovelFireProvider(), NovelsOnlineProvider(),
            LnoriProvider(), EmpireNovelProvider(), RoyalRoadProvider(),
            WebnovelProvider(), LightNovelWorldProvider(), ScribblehubProvider(),
            ReadNovelFullProvider(), WuxiaBoxProvider(), WtrLabProvider(),
            ChrysanthemumGardenProvider(), FenrirRealmProvider(), NovelBuddyProvider(),
            LightNovelTranslationsProvider(), LnMTLProvider(), MTLNovelProvider(),
            PawReadProvider(), RanobesProvider(), WattpadProvider(), NovelFullProvider(),
            WuxiaWorldProvider(), WuxiaClickProvider(), SonicMTLProvider(),
            HiraethTranslationProvider(), NovelDexProvider(), NovelLightProvider(),
            GrayCityProvider(), NoBadNovelProvider(), ReadHiveProvider(), OceanOfPDFProvider()
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
        val providers = listOf(
            AllNovelProvider(), NovelBinProvider(), LibReadProvider(),
            FreeWebNovelProvider(), NovelFireProvider(), NovelsOnlineProvider(),
            LnoriProvider(), EmpireNovelProvider(), RoyalRoadProvider(),
            WebnovelProvider(), LightNovelWorldProvider(), ScribblehubProvider(),
            ReadNovelFullProvider(), WuxiaBoxProvider(), WtrLabProvider(),
            ChrysanthemumGardenProvider(), FenrirRealmProvider(), NovelBuddyProvider(),
            LightNovelTranslationsProvider(), LnMTLProvider(), MTLNovelProvider(),
            PawReadProvider(), RanobesProvider(), WattpadProvider(), NovelFullProvider(),
            WuxiaWorldProvider(), WuxiaClickProvider(), SonicMTLProvider(),
            HiraethTranslationProvider(), NovelDexProvider(), NovelLightProvider(),
            GrayCityProvider(), NoBadNovelProvider(), ReadHiveProvider(), OceanOfPDFProvider()
        )
        assert(providers.size == 35) { "Expected 35 providers, got ${providers.size}" }
        providers.forEach { provider ->
            assert(provider.name.isNotBlank()) { "Provider has blank name: $provider" }
            assert(provider.mainUrl.startsWith("https://")) { "${provider.name}: mainUrl should start with https://" }
        }
        println("All 35 providers instantiate correctly.")
    }
}
