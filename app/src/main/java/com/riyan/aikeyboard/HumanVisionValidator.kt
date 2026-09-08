package com.riyan.aikeyboard

/**
 * Shared quality gate for human-image search prompts returned by every Vision provider.
 * It rejects generic human descriptions so AiClient can fall through to another provider.
 * Adult wording may be direct when the subject is clearly adult and the detail is visually grounded.
 */
object HumanVisionValidator {
    fun isAcceptable(query: String): Boolean {
        val clean = query.trim().replace(Regex("\\s+"), " ")
        if (!looksHuman(clean)) return true

        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 18) return false

        fun has(pattern: String): Boolean =
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(clean)

        val hasClothing = has(
            "\\b(dress|gown|shorts|leggings|pants|trousers|skirt|miniskirt|top|shirt|blouse|camisole|bikini|lingerie|swimsuit|bodysuit|jacket|coat)\\b"
        )
        if (!hasClothing) return false

        var detailGroups = 0
        if (has("\\b(front view|rear view|back view|side view|from behind|facing camera|facing away)\\b")) detailGroups++
        if (has("\\b(satin|silk|leather|denim|lace|spandex|lycra|tight|fitted|bodycon|body-hugging|skin-tight|plunging|deep v|thin straps|spaghetti straps)\\b")) detailGroups++
        if (has("\\b(cleavage|bust|breasts|defined waist|curvy hips|wide hips|buttocks|booty|thigh|thighs|bare shoulders|long legs)\\b")) detailGroups++
        if (has("\\b(long|short|wavy|curly|straight|black|brown|blonde|red)\\s+hair\\b")) detailGroups++
        if (has("\\b(bedroom|living room|art gallery|gallery|museum|street|outdoors|indoors|park|garden|studio)\\b")) detailGroups++
        if (has("\\b(standing|sitting|seated|walking|posing|leaning|kneeling)\\b")) detailGroups++

        if (detailGroups < 4) return false

        // Explicitly reject the failure pattern seen in testing: a generic adult label + one garment.
        if (Regex("(?i)^adult (woman|man),? sexy,? wearing .{0,55}(walking|standing)?$").matches(clean)) {
            return false
        }
        return true
    }

    private fun looksHuman(query: String): Boolean = Regex(
        "\\b(adult woman|adult man|woman|women|female|man|men|male|person|wanita|perempuan|pria|laki-laki)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(query)
}
