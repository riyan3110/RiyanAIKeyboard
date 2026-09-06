package com.riyan.aikeyboard

/**
 * Builds a grounded, detailed English search prompt for human images.
 *
 * This is intentionally shared by the AI Horde/Alchemy path so its output follows the same
 * detailed-human-search contract as native multimodal providers. It only uses visible evidence
 * present in the caption/tags and keeps age-uncertain subjects neutral.
 */
object HumanVisionPrompt {
    fun fromAlchemy(
        rawCaption: String,
        rawTags: List<Pair<String, Double>>,
        ageUnclear: Boolean
    ): String {
        val strongTags = rawTags.filter { it.second >= 0.18 }
        val source = (rawCaption + " " + strongTags.joinToString(" ") { it.first })
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

        fun has(pattern: String): Boolean =
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(source)

        val adultWoman = !ageUnclear && has("\\b(adult woman|woman|women|female)\\b")
        val adultMan = !ageUnclear && !adultWoman && has("\\b(adult man|man|men|male)\\b")
        val subject = when {
            adultWoman -> "adult woman"
            adultMan -> "adult man"
            else -> "person"
        }

        val sexyStyling = !ageUnclear && (
            has("\\b(sexy|sensual|seductive)\\b") ||
                (
                    has("\\b(satin|silk|bodycon|skin-tight|body-hugging|form-fitting|tight|fitted)\\b") &&
                        has("\\b(plunging|deep v|v-neck|cleavage|high slit|thigh slit|mini)\\b")
                    )
            )
        val style = when {
            sexyStyling -> "sexy"
            !ageUnclear && has("\\b(glamorous|elegant|fashionable)\\b") -> "glamorous"
            else -> ""
        }

        val color = when {
            has("\\bblack\\b") -> "black"
            has("\\bwhite\\b") -> "white"
            has("\\bred\\b") -> "red"
            has("\\bblue\\b") -> "blue"
            has("\\bgreen\\b") -> "green"
            has("\\b(gray|grey)\\b") -> "gray"
            has("\\b(beige|cream)\\b") -> "beige"
            has("\\bpink\\b") -> "pink"
            has("\\bpurple\\b") -> "purple"
            has("\\byellow\\b") -> "yellow"
            else -> ""
        }

        val material = when {
            has("\\bsatin\\b") -> "satin"
            has("\\bsilk\\b") -> "silk"
            has("\\bleather\\b") -> "leather"
            has("\\bdenim\\b") -> "denim"
            has("\\blace\\b") -> "lace"
            has("\\b(knit|knitted)\\b") -> "knit"
            has("\\bcotton\\b") -> "cotton"
            else -> ""
        }

        val fit = when {
            has("\\b(bodycon|skin-tight|body-hugging|form-fitting|tight|fitted|close-fitting)\\b") -> "fitted"
            has("\\b(loose|oversized)\\b") -> "loose"
            else -> ""
        }

        val garment = when {
            has("\\b(slip dress|camisole dress)\\b") -> "slip dress"
            has("\\bbodycon dress\\b") -> "bodycon dress"
            has("\\b(dress|gown)\\b") -> "dress"
            has("\\b(mini skirt|miniskirt)\\b") -> "mini skirt"
            has("\\bskirt\\b") -> "skirt"
            has("\\b(leggings|yoga pants)\\b") -> "leggings"
            has("\\b(shorts|athletic shorts|gym shorts)\\b") -> "shorts"
            has("\\bbikini\\b") -> "bikini"
            has("\\b(swimsuit|one-piece swimsuit)\\b") -> "swimsuit"
            has("\\b(top|blouse|shirt)\\b") -> "top"
            else -> ""
        }

        val clothingBits = linkedSetOf<String>()
        if (fit.isNotBlank()) clothingBits += fit
        if (color.isNotBlank()) clothingBits += color
        if (material.isNotBlank()) clothingBits += material
        if (garment.isNotBlank()) clothingBits += garment
        val clothing = clothingBits.joinToString(" ")
        val clothingPhrase = when {
            clothing.isBlank() -> ""
            garment == "leggings" || garment == "shorts" -> "wearing $clothing"
            else -> "wearing a $clothing"
        }

        val visibleDetails = mutableListOf<String>()
        if (!ageUnclear && has("\\b(cleavage|decolletage)\\b")) {
            visibleDetails += "visible cleavage"
        }
        if (!ageUnclear && has("\\b(bare shoulders|off shoulder|off-shoulder|spaghetti straps|thin straps|strapless)\\b")) {
            visibleDetails += "bare shoulders"
        }
        if (!ageUnclear && has("\\b(hourglass|defined waist|narrow waist)\\b")) {
            visibleDetails += "defined waist"
        }
        if (!ageUnclear && has("\\b(curvy|curvy hips|wide hips|hourglass)\\b")) {
            visibleDetails += "curvy hips"
        }
        if (!ageUnclear && has("\\b(thigh-high slit|thigh high slit|high slit|leg slit|exposed thigh)\\b")) {
            visibleDetails += "exposed thigh through a high slit"
        }
        if (has("\\b(plunging neckline|deep v-neck|deep v neck|v-neckline)\\b")) {
            visibleDetails += "deep V-neckline"
        }
        if (has("\\b(spaghetti straps|thin straps)\\b")) {
            visibleDetails += "thin shoulder straps"
        }

        val hairLength = when {
            has("\\blong hair\\b") -> "long"
            has("\\bshort hair\\b") -> "short"
            has("\\b(medium hair|shoulder-length hair)\\b") -> "shoulder-length"
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
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .let { if (it.isBlank()) "" else "$it hair" }

        val view = when {
            has("\\b(from behind|seen from behind|back view|rear view|facing away|back to camera)\\b") -> "rear view"
            has("\\b(side view|from the side|profile view)\\b") -> "side view"
            has("\\b(front view|from the front|facing camera|looking at camera)\\b") -> "front view"
            else -> ""
        }
        val pose = when {
            has("\\b(walking|walks)\\b") -> "walking"
            has("\\b(sitting|seated)\\b") -> "sitting"
            has("\\b(standing|stands)\\b") -> "standing"
            else -> ""
        }
        val scene = when {
            has("\\b(art gallery|gallery|museum|art exhibition)\\b") -> "in an art gallery"
            has("\\b(street|city street)\\b") -> "on a street"
            has("\\b(outdoors|outdoor|park|garden)\\b") -> "outdoors"
            has("\\bbedroom\\b") -> "in a bedroom"
            has("\\bliving room\\b") -> "in a living room"
            else -> ""
        }

        return buildList {
            add(subject)
            if (style.isNotBlank()) add(style)
            if (clothingPhrase.isNotBlank()) add(clothingPhrase)
            addAll(visibleDetails.distinct())
            if (hair.isNotBlank()) add(hair)
            if (view.isNotBlank()) add(view)
            if (pose.isNotBlank()) add(pose)
            if (scene.isNotBlank()) add(scene)
        }
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .replace(Regex("\\s+"), " ")
            .take(240)
            .trim(' ', ',')
    }
}
