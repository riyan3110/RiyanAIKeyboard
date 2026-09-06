from pathlib import Path
import re

p = Path(__file__).resolve().parents[1] / "app/src/main/java/com/riyan/aikeyboard/AiHordeAlchemyVision.kt"
s = p.read_text()

replacement = r'''    private fun bestBodyShapeDescription(
        rawCaption: String,
        rawTags: List<Pair<String, Double>>,
        clothing: String,
        view: String,
        ageUnclear: Boolean
    ): String {
        if (ageUnclear) return ""
        val adultEvidence = Regex("""\b(adult woman|woman|women|female)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(rawCaption) || rawTags.any { (text, confidence) ->
                confidence >= 0.35 && Regex("""\b(woman|women|female)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
            }
        if (!adultEvidence) return ""

        val usableTags = rawTags.filter { it.second >= 0.18 }
        val source = (rawCaption.lowercase() + " " + usableTags.joinToString(" ") { it.first.lowercase() })
            .replace(Regex("""\s+"""), " ")
        val lowerBodyCovered = clothing.contains("celana") || clothing.contains("legging") ||
            clothing.contains("rok") || clothing.contains("gaun") || clothing.contains("baju renang") ||
            clothing.contains("bikini") || clothing.contains("pakaian bawah")

        val buttPattern = Regex("""\b(ass|butt|buttocks|booty|backside)\b""", RegexOption.IGNORE_CASE)
        val sizePattern = Regex("""\b(big|large|prominent|full|curvy|thick|thicc|round|voluptuous|shapely)\b""", RegexOption.IGNORE_CASE)
        val buttVisible = buttPattern.containsMatchIn(rawCaption) || usableTags.any { buttPattern.containsMatchIn(it.first) }
        val sizeEvidence = sizePattern.containsMatchIn(rawCaption) || usableTags.any { sizePattern.containsMatchIn(it.first) }
        val explicitlyLargeButt = Regex(
            """\b(big|large|prominent|full|curvy|thick|thicc|round|voluptuous|shapely)\s+(ass|butt|buttocks|booty|backside)\b|\b(ass|butt|buttocks|booty|backside)\s+(looks|appears|is)?\s*(big|large|prominent|full|round|curvy|thick)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(source)

        return when {
            view == "dari belakang" && lowerBodyCovered && buttVisible && (explicitlyLargeButt || sizeEvidence) ->
                "bokong terlihat besar"
            view == "dari belakang" && lowerBodyCovered && buttVisible -> "bokong terlihat"
            else -> ""
        }
    }

'''
pattern = r"    private fun bestBodyShapeDescription\(.*?(?=    private fun bestColorDescription\()"
s2, count = re.subn(pattern, lambda _: replacement, s, count=1, flags=re.S)
if count != 1:
    raise SystemExit(f"body shape target not found: {count}")
p.write_text(s2)
print("Vision regex repaired")
