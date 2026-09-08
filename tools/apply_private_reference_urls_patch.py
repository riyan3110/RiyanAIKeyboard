from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AI_CLIENT = ROOT / "app/src/main/java/com/riyan/aikeyboard/AiClient.kt"


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if new in source:
        return source
    if old not in source:
        raise RuntimeError(f"reference URL patch marker not found: {label}")
    return source.replace(old, new, 1)


text = AI_CLIENT.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''    private fun shouldUseAutomaticSources(query: String): Boolean {\n        val clean = query.trim().lowercase()\n        if (clean.length < 4) return false\n        if ('?' in clean) return true\n        return AUTO_SEARCH_HINTS.any { hint ->\n            clean == hint || clean.startsWith("$hint ") || clean.contains(" $hint ")\n        }\n    }''',
    '''    private fun shouldUseAutomaticSources(query: String): Boolean {\n        val clean = query.trim().lowercase()\n        if (clean.length < 4) return false\n        if ('?' in clean) return true\n        if (needsFreshness(clean)) return true\n        return AUTO_SEARCH_HINTS.any { hint ->\n            clean == hint || clean.startsWith("$hint ") || clean.contains(" $hint ")\n        }\n    }''',
    "automatic-source trigger",
)

text = replace_once(
    text,
    '''    private val AUTO_SEARCH_HINTS = listOf(\n        "apa", "siapa", "kapan", "dimana", "di mana", "berapa", "bagaimana", "kenapa", "mengapa",\n        "cari", "carikan", "cek", "jelaskan", "tentang", "info", "informasi", "berita", "terbaru",\n        "update", "harga", "rilis", "versi", "what", "who", "when", "where", "how", "why",\n        "search", "find", "latest", "news", "release", "version", "about", "explain"\n    )''',
    '''    private val AUTO_SEARCH_HINTS = listOf(\n        "apa", "siapa", "kapan", "dimana", "di mana", "berapa", "bagaimana", "kenapa", "mengapa",\n        "cari", "carikan", "cek", "jelaskan", "tentang", "info", "informasi", "berita", "terbaru",\n        "update", "harga", "rilis", "versi", "jadwal", "skor", "pertandingan", "bola", "sepak bola",\n        "malam ini", "hari ini", "besok", "live", "langsung", "what", "who", "when", "where", "how", "why",\n        "search", "find", "latest", "news", "release", "version", "about", "explain"\n    )''',
    "automatic-source hints",
)

text = replace_once(
    text,
    '''    private val FRESHNESS_HINTS = listOf(\n        "terbaru", "hari ini", "sekarang", "terkini", "update", "berita", "rilis", "versi terbaru",\n        "latest", "today", "current", "news", "release", "new version"\n    )''',
    '''    private val FRESHNESS_HINTS = listOf(\n        "terbaru", "hari ini", "malam ini", "besok", "sekarang", "terkini", "update", "berita", "rilis",\n        "versi terbaru", "jadwal", "skor", "pertandingan", "kickoff", "kick off", "live",\n        "latest", "today", "tonight", "tomorrow", "current", "news", "release", "new version", "schedule", "score"\n    )''',
    "freshness hints",
)

old_fetch = '''    private fun fetchReferenceSources(urls: List<String>, query: String): String {\n        if (urls.isEmpty() || query.isBlank()) return ""\n        val encodedQuery = URLEncoder.encode(query.take(500), Charsets.UTF_8.name())\n        return urls.asSequence()\n            .map(String::trim)\n            .filter(String::isNotBlank)\n            .take(MAX_REFERENCE_URLS)\n            .mapNotNull { template ->\n                val expanded = template.replace("{query}", encodedQuery, ignoreCase = true)\n                runCatching { fetchReference(expanded) }.getOrNull()\n            }\n            .joinToString("\\n\\n") { (url, content) -> "[Sumber: $url]\\n$content" }\n            .take(MAX_TOTAL_REFERENCE_CHARS)\n    }'''

new_fetch = '''    private fun fetchReferenceSources(urls: List<String>, query: String): String {\n        if (urls.isEmpty() || query.isBlank()) return ""\n\n        val effectiveQuery = if (needsFreshness(query)) {\n            "$query ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}"\n        } else {\n            query\n        }\n        val encodedQuery = URLEncoder.encode(effectiveQuery.take(500), Charsets.UTF_8.name())\n        val references = mutableListOf<Pair<String, String>>()\n        val seenUrls = LinkedHashSet<String>()\n\n        urls.asSequence()\n            .map(String::trim)\n            .filter(String::isNotBlank)\n            .take(MAX_REFERENCE_URLS)\n            .forEach { template ->\n                if (references.size >= MAX_REFERENCE_URLS) return@forEach\n\n                manualReferenceCandidates(template, effectiveQuery, encodedQuery).forEach { candidate ->\n                    if (references.size >= MAX_REFERENCE_URLS) return@forEach\n                    if (!seenUrls.add(candidate)) return@forEach\n                    runCatching { fetchReference(candidate) }.getOrNull()?.let(references::add)\n                }\n            }\n\n        return references\n            .joinToString("\\n\\n") { (url, content) -> "[Sumber tersimpan: $url]\\n$content" }\n            .take(MAX_TOTAL_REFERENCE_CHARS)\n    }\n\n    private fun manualReferenceCandidates(\n        template: String,\n        query: String,\n        encodedQuery: String\n    ): List<String> {\n        val clean = template.trim()\n        if (clean.isBlank()) return emptyList()\n\n        if (clean.contains("{query}", ignoreCase = true)) {\n            return listOf(clean.replace("{query}", encodedQuery, ignoreCase = true))\n        }\n\n        val savedUrl = runCatching { URL(clean) }.getOrNull() ?: return emptyList()\n        if (!isAllowedPublicUrl(savedUrl)) return emptyList()\n\n        val savedHost = savedUrl.host.lowercase().removePrefix("www.")\n        if (savedHost.isBlank()) return listOf(clean)\n\n        // A plain saved URL used to fetch only its homepage. For questions such as current\n        // football schedules that often returns navigation/marketing text instead of the answer.\n        // Search inside the saved domain first, then keep the original URL as a fallback.\n        val scopedResults = discoverPublicSources("site:$savedHost $query")\n            .filter { candidate ->\n                val candidateHost = runCatching {\n                    URL(candidate).host.lowercase().removePrefix("www.")\n                }.getOrNull() ?: return@filter false\n                candidateHost == savedHost || candidateHost.endsWith(".$savedHost")\n            }\n            .take(2)\n\n        return buildList {\n            addAll(scopedResults)\n            add(clean)\n        }.distinct()\n    }'''

text = replace_once(text, old_fetch, new_fetch, "manual reference sources")

AI_CLIENT.write_text(text, encoding="utf-8")

checks = {
    "fresh automatic trigger": "if (needsFreshness(clean)) return true" in text,
    "sports hints": '"jadwal", "skor", "pertandingan", "bola"' in text,
    "saved-domain search": 'discoverPublicSources("site:$savedHost $query")' in text,
    "saved source label": '"[Sumber tersimpan: $url]' in text,
}
missing = [name for name, ok in checks.items() if not ok]
if missing:
    raise RuntimeError("reference URL patch incomplete: " + ", ".join(missing))

print("PRIVATE reference URL patch applied: fresh-query trigger + saved-domain scoped search")
