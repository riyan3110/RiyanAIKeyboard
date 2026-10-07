package com.riyan.aikeyboard

internal object AiAnswerFormat {
    const val INSTRUCTION = "FORMAT JAWABAN: Gunakan teks biasa yang rapi tanpa Markdown. " +
        "Jangan tambahkan tanda ** untuk penebalan pada jawaban atau balasan apa pun. " +
        "Gunakan paragraf pendek dengan satu baris kosong antarparagraf. " +
        "Jika perlu daftar, gunakan penomoran atau tanda •, satu poin per baris. " +
        "Jangan gunakan heading # atau tabel Markdown. Pertahankan isi dan maksud jawaban."

    fun clean(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("**", "")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
