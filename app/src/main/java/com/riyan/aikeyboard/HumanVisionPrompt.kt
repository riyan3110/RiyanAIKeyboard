package com.riyan.aikeyboard

/**
 * Builds a detailed, grounded English search prompt for human images.
 * Adult/sexualized styling is used only for clearly adult subjects and only when visually supported.
 */
object HumanVisionPrompt {
    fun fromAlchemy(
        rawCaption: String,
        rawTags: List<Pair<String, Double>>,
        ageUnclear: Boolean
    ): String {
        val strongTags = rawTags.filter { it.second >= 0.16 }
        val source = (rawCaption + " " + strongTags.joinToString(" ") { it.first })
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

        fun has(pattern: String): Boolean =
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(source)

        fun tagHas(pattern: String, minConfidence: Double = 0.18): Boolean =
            rawTags.any { (text, confidence) ->
                confidence >= minConfidence && Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
            }

        val adultWoman = !ageUnclear && has("\\b(adult woman|woman|women|female)\\b")
        val adultMan = !ageUnclear && !adultWoman && has("\\b(adult man|man|men|male)\\b")
        val subject = when {
            adultWoman -> "adult woman"
            adultMan -> "adult man"
            else -> "person"
        }

        val rearEvidence = has("\\b(from behind|seen from behind|back view|rear view|facing away|back to camera|turned away|backside)\\b") ||
            tagHas("\\b(ass|butt|buttocks|booty|backside)\\b")
        val frontEvidence = has("\\b(front view|from the front|facing camera|looking at camera|face visible)\\b")
        val sideEvidence = has("\\b(side view|from the side|profile view)\\b")
        val view = when {
            rearEvidence && !frontEvidence -> "rear view"
            sideEvidence -> "side view"
            frontEvidence -> "front view"
            else -> ""
        }

        val pose = when {
            has("\\b(sitting|seated)\\b") -> "sitting"
            has("\\b(standing|stands)\\b") -> "standing"
            has("\\b(walking|walks)\\b") && !rearEvidence -> "walking"
            else -> ""
        }

        val colors = listOf("black", "white", "gray", "grey", "red", "blue", "green", "beige", "cream", "pink", "purple", "yellow", "brown")
        fun normalizeColor(value: String): String = when (value.lowercase()) {
            "grey" -> "gray"
            "cream" -> "beige"
            else -> value.lowercase()
        }

        fun colorFor(garmentPattern: String): String {
            val colorPattern = colors.joinToString("|")
            val patterns = listOf(
                Regex("\\b($colorPattern)\\s+(?:tight\\s+|fitted\\s+|bodycon\\s+|skin-tight\\s+)?(?:$garmentPattern)\\b", RegexOption.IGNORE_CASE),
                Regex("\\b(?:$garmentPattern)\\s+(?:in\\s+)?($colorPattern)\\b", RegexOption.IGNORE_CASE)
            )
            patterns.forEach { regex ->
                regex.find(source)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return normalizeColor(it) }
            }
            return ""
        }

        fun materialFor(garmentPattern: String): String {
            val materials = "satin|silk|leather|denim|lace|cotton|spandex|lycra|knit|knitted"
            Regex("\\b($materials)\\s+(?:tight\\s+|fitted\\s+)?(?:$garmentPattern)\\b", RegexOption.IGNORE_CASE)
                .find(source)?.groupValues?.getOrNull(1)?.let { return it.lowercase().replace("knitted", "knit") }
            Regex("\\b(?:$garmentPattern)\\s+(?:made of\\s+|in\\s+)?($materials)\\b", RegexOption.IGNORE_CASE)
                .find(source)?.groupValues?.getOrNull(1)?.let { return it.lowercase().replace("knitted", "knit") }
            return ""
        }

        fun fittedFor(garmentPattern: String): Boolean =
            Regex("\\b(?:tight|fitted|form-fitting|skin-tight|body-hugging|close-fitting|stretch|stretchy)\\s+(?:$garmentPattern)\\b", RegexOption.IGNORE_CASE).containsMatchIn(source) ||
                Regex("\\b(?:$garmentPattern)\\b.{0,18}\\b(tight|fitted|form-fitting|skin-tight|body-hugging|close-fitting)\\b", RegexOption.IGNORE_CASE).containsMatchIn(source)

        val topPattern = "camisole|tank top|crop top|halter top|blouse|shirt|top"
        val lowerPattern = "short shorts|athletic shorts|gym shorts|shorts|leggings|yoga pants|mini skirt|miniskirt|skirt|pants|trousers"
        val dressPattern = "slip dress|camisole dress|bodycon dress|dress|gown"

        val topGarment = when {
            has("\\bcamisole\\b") -> "camisole top"
            has("\\btank top\\b") -> "tank top"
            has("\\bcrop top\\b") -> "crop top"
            has("\\bhalter top\\b") -> "halter top"
            has("\\bblouse\\b") -> "blouse"
            has("\\bshirt\\b") -> "shirt"
            has("\\btop\\b") -> "top"
            else -> ""
        }
        val lowerGarment = when {
            has("\\b(short shorts|hot pants)\\b") -> "short shorts"
            has("\\b(athletic shorts|gym shorts)\\b") -> "athletic shorts"
            has("\\bshorts\\b") -> "shorts"
            has("\\b(leggings|yoga pants)\\b") -> "leggings"
            has("\\b(mini skirt|miniskirt)\\b") -> "mini skirt"
            has("\\bskirt\\b") -> "skirt"
            has("\\b(pants|trousers)\\b") -> "pants"
            else -> ""
        }
        val dressGarment = when {
            has("\\b(slip dress|camisole dress)\\b") -> "slip dress"
            has("\\bbodycon dress\\b") -> "bodycon dress"
            has("\\b(dress|gown)\\b") -> "dress"
            else -> ""
        }

        fun garmentPhrase(garment: String, pattern: String): String {
            if (garment.isBlank()) return ""
            val color = colorFor(pattern)
            val material = materialFor(pattern)
            val fitted = fittedFor(pattern)
            return listOf(
                if (fitted) "tight" else "",
                color,
                material,
                garment
            ).filter { it.isNotBlank() }.distinct().joinToString(" ")
        }

        var topPhrase = garmentPhrase(topGarment, topPattern)
        var lowerPhrase = garmentPhrase(lowerGarment, lowerPattern)
        val dressPhrase = garmentPhrase(dressGarment, dressPattern)

        // Keep top/bottom colors separate. The old global-color logic could turn gray shorts white.
        if (topGarment.isNotBlank() && lowerGarment.isNotBlank()) {
            if (topPhrase.contains("white") && !lowerPhrase.contains(Regex("\\b(black|white|gray|red|blue|green|beige|pink|purple|yellow|brown)\\b")) && has("\\b(gray|grey)\\b")) {
                lowerPhrase = "gray $lowerPhrase"
            }
            if (lowerPhrase.contains("white") && topPhrase.isBlank() && has("\\b(gray|grey)\\b")) {
                lowerPhrase = lowerPhrase.replace("white", "gray")
            }
        }

        val clothingPhrase = when {
            dressPhrase.isNotBlank() -> "wearing a $dressPhrase"
            topPhrase.isNotBlank() && lowerPhrase.isNotBlank() -> "wearing a $topPhrase and $lowerPhrase"
            lowerPhrase.isNotBlank() -> "wearing $lowerPhrase"
            topPhrase.isNotBlank() -> "wearing a $topPhrase"
            else -> ""
        }

        val tightLower = lowerGarment.isNotBlank() && (fittedFor(lowerPattern) || has("\\b(spandex|lycra|body-hugging|skin-tight)\\b"))
        val buttEvidence = rearEvidence && tagHas("\\b(ass|butt|buttocks|booty|backside)\\b", 0.16)
        val sexualFashionCue = !ageUnclear && (adultWoman || adultMan) && (
            tightLower || fittedFor(dressPattern) || fittedFor(topPattern) ||
                has("\\b(cleavage|decolletage|plunging|deep v|high slit|thigh slit|mini skirt|bodycon|bikini|lingerie|sensual|sexy|seductive)\\b") ||
                buttEvidence
            )
        val style = when {
            sexualFashionCue -> "sexy"
            !ageUnclear && has("\\b(glamorous|elegant|fashionable)\\b") -> "glamorous"
            else -> ""
        }

        val visibleDetails = mutableListOf<String>()
        if (!ageUnclear && has("\\b(cleavage|decolletage)\\b")) visibleDetails += "visible cleavage"
        if (!ageUnclear && has("\\b(hourglass|defined waist|narrow waist|small waist)\\b")) visibleDetails += "defined waist"
        if (!ageUnclear && has("\\b(curvy|wide hips|curvy hips|hourglass|thick hips)\\b")) visibleDetails += "curvy hips"
        if (!ageUnclear && buttEvidence && tightLower) visibleDetails += "buttocks outlined through tight shorts"
        if (!ageUnclear && has("\\b(big butt|large butt|prominent buttocks|round buttocks|big booty|large booty)\\b") && rearEvidence) visibleDetails += "prominent buttocks"
        if (lowerGarment.contains("shorts") && has("\\b(thigh|thighs|bare legs|legs)\\b")) visibleDetails += "exposed upper thighs"
        if (!ageUnclear && has("\\b(high slit|thigh slit|exposed thigh)\\b")) visibleDetails += "exposed thigh"
        if (has("\\b(plunging neckline|deep v-neck|deep v neck|v-neckline)\\b")) visibleDetails += "deep V-neckline"
        if (has("\\b(spaghetti straps|thin straps)\\b")) visibleDetails += "thin shoulder straps"

        val hairLength = when {
            has("\\blong hair\\b") -> "long"
            has("\\bshort hair\\b") -> "short"
            has("\\b(shoulder-length hair|medium hair)\\b") -> "shoulder-length"
            else -> ""
        }
        val hairColor = when {
            has("\\bblack hair\\b") -> "black"
            has("\\b(dark brown hair|brown hair)\\b") -> "dark brown"
            has("\\b(blonde hair|blond hair)\\b") -> "blonde"
            has("\\bred hair\\b") -> "red"
            else -> ""
        }
        val hairTexture = when {
            has("\\bwavy hair\\b") -> "wavy"
            has("\\bcurly hair\\b") -> "curly"
            has("\\bstraight hair\\b") -> "straight"
            else -> ""
        }
        val hair = listOf(hairLength, hairColor, hairTexture)
            .filter { it.isNotBlank() }.distinct().joinToString(" ")
            .let { if (it.isBlank()) "" else "$it hair" }

        val scene = when {
            has("\\bbedroom\\b") -> "in a bedroom"
            has("\\b(living room|indoors|indoor room)\\b") -> "indoors"
            has("\\b(art gallery|gallery|museum|art exhibition)\\b") -> "in an art gallery"
            has("\\b(street|city street)\\b") -> "on a street"
            has("\\b(outdoors|outdoor|park|garden)\\b") -> "outdoors"
            else -> ""
        }

        val result = buildList {
            add(subject)
            if (style.isNotBlank()) add(style)
            if (view.isNotBlank()) add(view)
            if (clothingPhrase.isNotBlank()) add(clothingPhrase)
            addAll(visibleDetails.distinct())
            if (hair.isNotBlank()) add(hair)
            if (pose.isNotBlank()) add(pose)
            if (scene.isNotBlank()) add(scene)
        }.filter { it.isNotBlank() }
            .joinToString(", ")
            .replace(Regex("\\s+"), " ")
            .take(300)
            .trim(' ', ',')

        // Alchemy is noisy. Reject generic human output so a stronger vision provider can try.
        val isAdult = adultWoman || adultMan
        if (isAdult && (view.isBlank() || clothingPhrase.isBlank())) return ""
        if (isAdult && result.split(Regex("\\s+")).size < 9) return ""
        return result
    }
}
