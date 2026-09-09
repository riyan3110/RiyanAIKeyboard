package com.riyan.aikeyboard

internal object PrivateVoiceQuery {
    const val INSTRUCTION = "Anda hanya penyunting transkripsi suara untuk pencarian web. Prioritas mutlak adalah mempertahankan kata yang benar-benar ada pada transkripsi. Rapikan kapitalisasi dan tanda baca. Anda boleh memperbaiki paling banyak SATU kata hanya jika itu jelas salah dengar/typo kecil dan bentuk katanya sangat mirip dengan teks asli. Jangan mengganti kata dengan sinonim, jangan menyusun ulang, jangan menerjemahkan, jangan menambah atau menghapus kata, jangan menjawab pertanyaan, jangan menambah kata kunci, dan jangan menebak maksud yang tidak tertulis. Pertahankan nama produk, nama orang, lokasi, angka, slang, istilah asing, dan seluruh detail ucapan. Jika ragu, kembalikan teks asli apa adanya. Kembalikan hanya satu kalimat pencarian tanpa pengantar, penjelasan, kutipan atau markdown."

    fun choose(original: String, corrected: String?): String {
        val source = original.trim()
        val candidate = corrected?.trim()?.trim('"', '“', '”').orEmpty()
        if (source.isBlank()) return candidate
        if (candidate.isBlank() || candidate.contains('\n') || candidate.contains("```")) return source
        if (candidate.length > source.length * 1.35 + 12 || candidate.length < source.length * 0.75) return source

        // Numbers, years, prices, model numbers, and quantities must never silently change.
        val numbers = Regex("\\d+")
        if (numbers.findAll(source).map { it.value }.toList() != numbers.findAll(candidate).map { it.value }.toList()) return source

        val before = tokens(source)
        val after = tokens(candidate)
        if (before.isEmpty() || before.size != after.size) return source

        val changed = before.indices.filter { before[it] != after[it] }
        if (changed.isEmpty()) return candidate // punctuation/case-only cleanup is safe.
        if (changed.size != 1) return source

        val index = changed.single()
        val from = before[index]
        val to = after[index]
        // A single word correction is accepted only when it is a very close spelling match.
        // This blocks AI paraphrasing such as "cari" -> "harga" or product/name substitutions.
        if (wordSimilarity(from, to) < 0.76) return source
        return candidate
    }

    private fun tokens(value: String): List<String> = Regex("[\\p{L}\\p{N}]+")
        .findAll(value.lowercase())
        .map { it.value }
        .toList()

    private fun wordSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            for (j in previous.indices) previous[j] = current[j]
        }
        return 1.0 - previous[b.length].toDouble() / longest.toDouble()
    }
}
