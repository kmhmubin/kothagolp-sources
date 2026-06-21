# kothagolp-sources

Source plugin APK for the **Kothagolp** novel reader app. Contains 19 web novel providers loaded at runtime via `DexClassLoader` — no host app update needed to add or fix sources.

---

## Current Release

**v12 · 19 providers**
[Download latest sources.apk](https://github.com/kmhmubin/kothagolp-sources/releases/latest/download/sources.apk)

| Provider | Site |
|----------|------|
| AllNovel | allnovel.org |
| Cyrisia | cyrisia.com |
| FenrirRealm | fenrirrealm.com |
| FreeWebNovel | freewebnovel.com |
| FuckNovelPia | fucknovelpia.com |
| LibRead | libread.com |
| LightNovelTranslations | lightnoveltranslations.com |
| LightNovelWorld | lightnovelworld.co |
| Lnori | lnori.com |
| Novel Archive | novelarchive.cc |
| NovelBin | novelbin.me |
| Novel Buddy | novelbuddy.io |
| NovelDex | noveldex.io |
| NovelFire | novelfire.net |
| NovelsOnline | novelsonline.net |
| PawRead | pawread.com |
| Ranobes | ranobes.net |
| Royal Road | royalroad.com |
| Webnovel | webnovel.com |

---

## Project Structure

```
kothagolp-sources/
├── source-api/          # Shared contracts (MainProvider, models, NetworkClient stub)
│                        # compileOnly — NOT bundled in APK
├── sources/             # All provider implementations
│   └── src/main/java/com/kmhmubin/kothagolp/sources/
│       └── XxxProvider.kt   # One file per source
├── manifest.json        # Version + provider list (auto-updated by CI)
├── SOURCE_TEMPLATE.md   # Full guide for building/fixing providers
└── README.md
```

---

## Development

### Requirements
- JDK 17
- Android SDK (compileSdk 35)

### Build debug APK
```bash
./gradlew :sources:assembleDebug
# Output: sources/build/outputs/apk/debug/sources-debug.apk
```

### Run tests (no network)
```bash
./gradlew :sources:testDebugUnitTest
```

### Run network tests for a specific provider
```bash
RUN_SOURCE_TESTS=true ./gradlew :sources:testDebugUnitTest \
  --tests "*.SourceEvaluationTest.testExampleProvider"
```

---

## Adding a New Source

See **[SOURCE_TEMPLATE.md](SOURCE_TEMPLATE.md)** for the full guide including:
- Provider skeleton and method contracts
- HTML scraping and REST API patterns
- Common pitfalls and fixes
- Step-by-step checklist

---

## CI/CD

Pushing to `main` without `[skip ci]` in the commit message triggers the build workflow which:
1. Builds release APK
2. Increments version in `manifest.json` and syncs provider list
3. Creates a GitHub release with `sources.apk`
4. Commits updated manifest with `[skip ci]`
