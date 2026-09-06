from pathlib import Path

# ---- AI Horde Alchemy ----
p = Path('app/src/main/java/com/riyan/aikeyboard/AiHordeAlchemyVision.kt')
s = p.read_text()

s = s.replace(
'''        val clothing = bestClothingDescription(translatedCaption, translatedTagTexts)\n        val color = bestColorDescription(translatedCaption, translatedTagTexts)\n        val view = bestViewDescription(rawCaption, rawTags)\n        val bodyShape = bestBodyShapeDescription(rawCaption, rawTags, clothing, view, ageUnclear)\n\n        val tokens = mutableListOf<String>()\n        tokens += subject\n        if (view.isNotBlank()) tokens += view.split(Regex("\\\\s+"))\n        if (clothing.isNotBlank()) {\n            tokens += clothing.split(Regex("\\\\s+"))\n''',
'''        val clothing = bestClothingDescription(translatedCaption, translatedTagTexts)\n        val color = bestColorDescription(translatedCaption, translatedTagTexts)\n        val view = bestViewDescription(rawCaption, rawTags)\n        val bodyShape = bestBodyShapeDescription(rawCaption, rawTags, clothing, view, ageUnclear)\n\n        val tokens = mutableListOf<String>()\n        tokens += subject\n        if (view.isNotBlank()) tokens += view.split(Regex("\\\\s+"))\n        if (clothing.isNotBlank()) {\n            tokens += "pakai"\n            tokens += clothing.split(Regex("\\\\s+"))\n''', 1)

s = s.replace('.take(9)\n            .joinToString(" ")\n            .take(80)', '.take(10)\n            .joinToString(" ")\n            .take(96)', 1)

old = '''    private fun bestViewDescription(rawCaption: String, rawTags: List<Pair<String, Double>>): String {\n        val strongTags = rawTags\n            .filter { it.second >= 0.42 }\n            .joinToString(" ") { it.first.lowercase() }\n        val source = (rawCaption.lowercase() + " " + strongTags).replace(Regex("\\\\s+"), " ")\n        return when {\n            Regex("\\\\b(from behind|seen from behind|back view|rear view|backside view|facing away)\\\\b", RegexOption.IGNORE_CASE)\n                .containsMatchIn(source) -> "dari belakang"\n            Regex("\\\\b(from the side|side view|profile view)\\\\b", RegexOption.IGNORE_CASE)\n                .containsMatchIn(source) -> "dari samping"\n            Regex("\\\\b(from the front|front view|facing camera)\\\\b", RegexOption.IGNORE_CASE)\n                .containsMatchIn(source) -> "dari depan"\n            else -> ""\n        }\n    }\n'''
new = '''    private fun bestViewDescription(rawCaption: String, rawTags: List<Pair<String, Double>>): String {\n        val strongTags = rawTags\n            .filter { it.second >= 0.30 }\n            .joinToString(" ") { it.first.lowercase() }\n        val source = (rawCaption.lowercase() + " " + strongTags).replace(Regex("\\\\s+"), " ")\n        val rearBodyEvidence = rawTags.any { (text, confidence) ->\n            confidence >= 0.34 && Regex("\\\\b(ass|butt|buttocks|booty|backside)\\\\b", RegexOption.IGNORE_CASE)\n                .containsMatchIn(text)\n        }\n        val frontEvidence = Regex("\\\\b(front view|from the front|facing camera|face visible|looking at camera)\\\\b", RegexOption.IGNORE_CASE)\n            .containsMatchIn(source)\n        return when {\n            Regex("\\\\b(from behind|seen from behind|back view|rear view|backside view|facing away|back to camera|turned away)\\\\b", RegexOption.IGNORE_CASE)\n                .containsMatchIn(source) -> "dari belakang"\n            rearBodyEvidence && !frontEvidence -> "dari belakang"\n            Regex("\\\\b(from the side|side view|profile view)\\\\b", RegexOption.IGNORE_CASE)\n                .containsMatchIn(source) -> "dari samping"\n            Regex("\\\\b(from the front|front view|facing camera)\\\\b", RegexOption.IGNORE_CASE)\n                .containsMatchIn(source) -> "dari depan"\n            else -> ""\n        }\n    }\n'''
if old not in s:
    raise SystemExit('bestViewDescription anchor not found')
s = s.replace(old, new, 1)

old = '''        val strongTags = rawTags\n            .filter { it.second >= 0.52 }\n            .joinToString(" ") { it.first.lowercase() }\n        val source = (rawCaption.lowercase() + " " + strongTags).replace(Regex("\\\\s+"), " ")\n'''
new = '''        val strongTags = rawTags\n            .filter { it.second >= 0.34 }\n            .joinToString(" ") { it.first.lowercase() }\n        val source = (rawCaption.lowercase() + " " + strongTags).replace(Regex("\\\\s+"), " ")\n'''
if old not in s:
    raise SystemExit('body strong tags anchor not found')
s = s.replace(old, new, 1)

old = '''        val clearlyLargeButt = Regex(\n            "\\\\b(big|large|prominent|full|curvy)\\\\s+(ass|butt|buttocks|booty)\\\\b|" +\n                "\\\\b(ass|butt|buttocks|booty)\\\\s+(looks|appears|is)?\\\\s*(big|large|prominent|full)\\\\b",\n            RegexOption.IGNORE_CASE\n        ).containsMatchIn(source)\n        return if (view == "dari belakang" && lowerBodyCovered && clearlyLargeButt) {\n            "bokong terlihat besar"\n        } else ""\n'''
new = '''        val buttVisible = Regex("\\\\b(ass|butt|buttocks|booty|backside)\\\\b", RegexOption.IGNORE_CASE)\n            .containsMatchIn(source)\n        val clearlyLargeButt = Regex(\n            "\\\\b(big|large|prominent|full|curvy|thick|thicc|round)\\\\s+(ass|butt|buttocks|booty|backside)\\\\b|" +\n                "\\\\b(ass|butt|buttocks|booty|backside)\\\\s+(looks|appears|is)?\\\\s*(big|large|prominent|full|round)\\\\b",\n            RegexOption.IGNORE_CASE\n        ).containsMatchIn(source)\n        return when {\n            view == "dari belakang" && lowerBodyCovered && clearlyLargeButt -> "bokong terlihat besar"\n            view == "dari belakang" && lowerBodyCovered && buttVisible -> "bokong terlihat"\n            else -> ""\n        }\n'''
if old not in s:
    raise SystemExit('body output anchor not found')
s = s.replace(old, new, 1)

# Clothing can be direct when Alchemy explicitly reports fitted/tight outerwear.
old = '''        priorities.firstOrNull { captionLower.contains(it) }?.let { return it }\n        priorities.firstOrNull { tagLower.contains(it) }?.let { return it }\n\n        // If an English tag slipped through translation, normalize common visible outerwear here.\n'''
new = '''        priorities.firstOrNull { captionLower.contains(it) }?.let { return it }\n        priorities.firstOrNull { tagLower.contains(it) }?.let { return it }\n\n        val tightShorts = Regex("\\\\b(tight|fitted|form-fitting|skin-tight|body-hugging|booty)\\\\s+shorts\\\\b", RegexOption.IGNORE_CASE)\n            .containsMatchIn(combined)\n        if (tightShorts) return "celana pendek ketat"\n\n        // If an English tag slipped through translation, normalize common visible outerwear here.\n'''
if old not in s:
    raise SystemExit('clothing anchor not found')
s = s.replace(old, new, 1)

# Keep direct adult body wording available as evidence; do not drop ordinary body words as "noise".
s = s.replace('"hers", "him", "she", "he", "girl", "boy", "ass", "panties", "living", "room",', '"hers", "him", "she", "he", "girl", "boy", "living", "room",', 1)

p.write_text(s)

# ---- General vision/chat tone ----
p = Path('app/src/main/java/com/riyan/aikeyboard/AiClient.kt')
s = p.read_text()
s = s.replace('bahasa Indonesia, 3–9 kata, maksimal sekitar 80 karakter', 'bahasa Indonesia, 3–10 kata, maksimal sekitar 96 karakter', 1)
s = s.replace('val maxWords = if (subject == "person") 9 else 7', 'val maxWords = if (subject == "person") 10 else 7', 1)
s = s.replace('val maxChars = if (subject == "person") 80 else 64', 'val maxChars = if (subject == "person") 96 else 64', 1)
needle = 'Jika pengguna memakai istilah seksual eksplisit, pahami maknanya dan pertahankan ragam bahasa yang sesuai konteks tanpa otomatis mengubahnya menjadi istilah kaku.'
replacement = needle + ' Untuk orang yang jelas dewasa, jangan menyamarkan kata tubuh biasa seperti bokong, payudara, pinggul, paha, atau bentuk pakaian; jika memang relevan, sebut secara langsung dan faktual tanpa eufemisme. Jangan mengarang bagian yang tertutup atau fakta yang tidak didukung.'
if needle not in s:
    raise SystemExit('chat direct wording anchor not found')
s = s.replace(needle, replacement, 1)
p.write_text(s)
