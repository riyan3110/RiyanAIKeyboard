from pathlib import Path

ai_path = Path('app/src/main/java/com/riyan/aikeyboard/AiClient.kt')
keyboard_path = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')

ai = ai_path.read_text(encoding='utf-8')
k = keyboard_path.read_text(encoding='utf-8')

# -----------------------------------------------------------------------------
# 1) Broader web research without making seven HTTP requests serially.
# -----------------------------------------------------------------------------
if 'import java.util.concurrent.Executors\n' not in ai:
    marker = 'import java.net.URLEncoder\n'
    if marker not in ai:
        raise RuntimeError('AiClient import marker not found')
    ai = ai.replace(marker, marker + 'import java.util.concurrent.Executors\nimport java.util.concurrent.TimeUnit\n', 1)

start = ai.find('    private fun fetchAutomaticSources(query: String): String {')
end = ai.find('\n    private fun discoverPublicSources(query: String): List<String> {', start)
if start < 0 or end < 0:
    raise RuntimeError('fetchAutomaticSources block not found')

new_fetch = r'''    private fun fetchAutomaticSources(query: String): String {
        val discovered = discoverPublicSources(query)
        val ranked = discovered
            .mapIndexed { index, url -> url to sourceScore(url, query, index) }
            .sortedByDescending { it.second }

        // Keep candidates from different domains so the AI receives genuinely independent sources.
        val usedHosts = LinkedHashSet<String>()
        val candidates = ranked.mapNotNull { (candidate, _) ->
            val host = runCatching { URL(candidate).host.lowercase().removePrefix("www.") }.getOrNull()
                ?: return@mapNotNull null
            if (!usedHosts.add(host)) return@mapNotNull null
            candidate
        }.take(MAX_FETCH_CANDIDATES)

        val references = mutableListOf<Pair<String, String>>()
        if (candidates.isNotEmpty()) {
            val workers = minOf(6, candidates.size).coerceAtLeast(1)
            val executor = Executors.newFixedThreadPool(workers)
            try {
                // Network reads happen in parallel. This raises source coverage without multiplying
                // the wait time by the number of websites.
                val futures = candidates.map { candidate ->
                    executor.submit<Pair<String, String>?> {
                        runCatching { fetchReference(candidate) }.getOrNull()
                    }
                }
                for (future in futures) {
                    if (references.size >= MAX_AUTOMATIC_SOURCES) break
                    val fetched = runCatching { future.get(10, TimeUnit.SECONDS) }.getOrNull()
                    if (fetched != null) references += fetched
                }
                futures.forEach { if (!it.isDone) it.cancel(true) }
            } finally {
                executor.shutdownNow()
            }
        }

        // Structured fallbacks are useful when normal search results are blocked or sparse.
        if (references.size < MIN_PREFERRED_SOURCES) {
            val existingHosts = references.mapNotNullTo(LinkedHashSet()) {
                runCatching { URL(it.first).host.lowercase().removePrefix("www.") }.getOrNull()
            }
            automaticFallbackUrls(query).forEach { fallback ->
                if (references.size >= MAX_AUTOMATIC_SOURCES) return@forEach
                val host = runCatching { URL(fallback).host.lowercase().removePrefix("www.") }.getOrNull()
                    ?: return@forEach
                if (!existingHosts.add(host)) return@forEach
                runCatching { fetchReference(fallback) }.getOrNull()?.let(references::add)
            }
        }

        return references
            .take(MAX_AUTOMATIC_SOURCES)
            .joinToString("\n\n") { (url, content) -> "[Sumber otomatis: $url]\n$content" }
            .take(MAX_TOTAL_REFERENCE_CHARS)
    }
'''
ai = ai[:start] + new_fetch + ai[end:]

# Fresh/current questions also inspect Bing News result pages, not only general web results.
old_pages = '''        val searchPages = listOf(
            "https://html.duckduckgo.com/html/?q=$encoded",
            "https://www.bing.com/search?q=$encoded"
        )'''
new_pages = '''        val searchPages = buildList {
            add("https://www.bing.com/search?q=$encoded")
            add("https://html.duckduckgo.com/html/?q=$encoded")
            if (needsFreshness(query)) {
                add("https://www.bing.com/news/search?q=$encoded")
            }
        }'''
if old_pages in ai:
    ai = ai.replace(old_pages, new_pages, 1)

# Raise source count and context budget. Seven independent sources is a useful ceiling on mobile:
# broad enough for cross-checking while still fitting common model context windows.
ai = ai.replace('    private const val MAX_AUTOMATIC_SOURCES = 3\n', '    private const val MAX_AUTOMATIC_SOURCES = 7\n')
ai = ai.replace('    private const val MAX_DISCOVERED_SOURCES = 24\n', '    private const val MAX_DISCOVERED_SOURCES = 48\n')
if 'MAX_FETCH_CANDIDATES' not in ai:
    marker = '    private const val MAX_AUTOMATIC_SOURCES = 7\n'
    ai = ai.replace(marker, marker + '    private const val MIN_PREFERRED_SOURCES = 5\n    private const val MAX_FETCH_CANDIDATES = 14\n', 1)
ai = ai.replace('    private const val MAX_REFERENCE_URLS = 6\n', '    private const val MAX_REFERENCE_URLS = 10\n')
ai = ai.replace('    private const val MAX_REFERENCE_CHARS_PER_URL = 4_000\n', '    private const val MAX_REFERENCE_CHARS_PER_URL = 4_200\n')
ai = ai.replace('    private const val MAX_TOTAL_REFERENCE_CHARS = 12_000\n', '    private const val MAX_TOTAL_REFERENCE_CHARS = 28_000\n')

# Tell the model to actually cross-check the larger source bundle instead of stopping after 2-3.
ai = ai.replace(
    'bandingkan 2–3 sumber yang relevan bila memungkinkan',
    'bandingkan 4–7 sumber independen yang relevan bila tersedia'
)

# -----------------------------------------------------------------------------
# 2) Ultra-fast typing path. At >=300% the slider is treated as a real performance mode.
# -----------------------------------------------------------------------------
if 'private var fastTypingMode = false' not in k:
    marker = '    private var instantKeyResponse = false\n'
    if marker not in k:
        raise RuntimeError('instantKeyResponse field not found')
    k = k.replace(marker, marker + '    private var fastTypingMode = false\n', 1)

# The v16 patch forces instant response. Add the performance-mode decision right after it.
old_load = '        instantKeyResponse = true\n'
new_load = '        instantKeyResponse = true\n        fastTypingMode = sensitivity >= 300\n'
if new_load not in k:
    if old_load not in k:
        raise RuntimeError('instant key load marker not found; v16 must run before v20')
    k = k.replace(old_load, new_load, 1)

# Selection callbacks are extremely noisy in TikTok/Compose editors. The key commit itself already
# schedules suggestions; don't schedule a duplicate callback in performance mode.
old_selection = '        refreshSuggestionsSoon()\n    }\n\n    override fun onCreateInputView()'
new_selection = '        if (!fastTypingMode) refreshSuggestionsSoon()\n    }\n\n    override fun onCreateInputView()'
if old_selection in k:
    k = k.replace(old_selection, new_selection, 1)

# Dynamic debounce: no prediction work competes with a fast typing burst.
old_refresh = '''    private fun refreshSuggestionsSoon() {
        if (!::suggestionBar.isInitialized) return
        handler.removeCallbacks(suggestionRefreshRunnable)
        handler.postDelayed(suggestionRefreshRunnable, 140L)
    }'''
if old_refresh not in k:
    old_refresh = '''    private fun refreshSuggestionsSoon() {
        if (!::suggestionBar.isInitialized) return
        handler.removeCallbacks(suggestionRefreshRunnable)
        handler.postDelayed(suggestionRefreshRunnable, 450L)
    }'''
new_refresh = '''    private fun refreshSuggestionsSoon() {
        if (!::suggestionBar.isInitialized || !suggestionsEnabled) return
        handler.removeCallbacks(suggestionRefreshRunnable)
        handler.postDelayed(suggestionRefreshRunnable, if (fastTypingMode) 700L else 220L)
    }'''
if old_refresh in k:
    k = k.replace(old_refresh, new_refresh, 1)
else:
    raise RuntimeError('refreshSuggestionsSoon block not found')

# In fast mode don't synchronously read text around the cursor and parse/save learning JSON on
# every space/punctuation. Those binder + storage operations were a major source of pauses.
old_learning = '''    private fun learnCurrentBoundary(completed: Boolean = false, terminalMark: String = "") {
        if (isSensitiveEditor()) return
        val before = textBeforeTypingCursor()'''
new_learning = '''    private fun learnCurrentBoundary(completed: Boolean = false, terminalMark: String = "") {
        if (fastTypingMode || isSensitiveEditor()) return
        val before = textBeforeTypingCursor()'''
if old_learning not in k:
    raise RuntimeError('learnCurrentBoundary marker not found')
k = k.replace(old_learning, new_learning, 1)

# v16 posts normal key feedback to the main-thread queue for every key. Under rapid typing this can
# build a backlog. At 300-400% skip sound/vibration on normal taps; long-press feedback remains.
k = k.replace(
    '                        handler.post { keyFeedback(view, longPress = false) }\n',
    '                        if (!fastTypingMode) handler.post { keyFeedback(view, longPress = false) }\n'
)

# Popup previews are already disabled in v18, so don't enqueue a no-op Runnable on every tap.
k = k.replace('''                    handler.post {
                        if (view.isAttachedToWindow) showKeyPreview(view, spec)
                    }
''', '')

# Remove per-tap translation/elevation animations. They cause view invalidation/layout work and add
# no value now that the user explicitly asked to remove raised key feedback.
for line in [
    '                    view.translationY = dpFloat(1.7f)\n',
    '                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) keyFace.elevation = dpFloat(0.6f)\n',
    '                    view.translationY = 0f\n',
    '                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) keyFace.elevation = dpFloat(2.6f)\n',
]:
    k = k.replace(line, '')

# If a normal-feedback call survives another formatting variant, guard it too.
k = k.replace(
    '                        keyFeedback(view, longPress = false)\n',
    '                        if (!fastTypingMode) keyFeedback(view, longPress = false)\n'
)

ai_path.write_text(ai, encoding='utf-8')
keyboard_path.write_text(k, encoding='utf-8')
print('Applied v0.18 v20: up to 7 parallel web sources and ultra-fast 300-400% key path.')
