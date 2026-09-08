package com.riyan.aikeyboard

import java.util.Locale

/** Small offline Indonesian lexicon; match whole words in the current sentence. */
internal object PrivateEmojiSuggestions {
    private val entries = buildMap<String, List<String>> {
        fun add(words: String, vararg emoji: String) {
            words.split(" ").forEach { put(it, emoji.toList()) }
        }
        add("kunci mengunci terkunci key", "🔑", "🔧", "🔐")
        add("gembok", "🔒", "🔐", "🔓")
        add("cinta sayang love hati", "❤️", "🥰", "😍")
        add("senang bahagia senyum", "😊", "😄", "🥰")
        add("ketawa tertawa lucu ngakak wkwk haha", "😂", "🤣", "😆")
        add("sedih menangis nangis", "😢", "😭", "🥺")
        add("marah kesal", "😠", "😤", "🤬")
        add("tidur ngantuk mengantuk", "😴", "🥱", "💤")
        add("makan lapar makanan", "🍽️", "😋", "🍚")
        add("minum haus", "🥤", "💧", "🧃")
        add("kopi", "☕", "🫘")
        add("teh", "🍵", "🫖")
        add("nasi", "🍚", "🍛")
        add("mie mi", "🍜", "🍝")
        add("ayam", "🐔", "🍗")
        add("ikan", "🐟", "🐠")
        add("kucing", "🐱", "🐈", "😻")
        add("anjing", "🐶", "🐕")
        add("bunga", "🌸", "🌹", "💐")
        add("hujan", "🌧️", "☔", "⛈️")
        add("panas matahari", "☀️", "🥵", "🌞")
        add("dingin", "🥶", "❄️")
        add("malam bulan", "🌙", "🌃", "✨")
        add("pagi", "🌅", "☀️")
        add("rumah", "🏠", "🏡")
        add("mobil", "🚗", "🚙")
        add("motor", "🏍️", "🛵")
        add("sepeda", "🚲")
        add("pesawat terbang", "✈️", "🛫")
        add("uang duit bayar", "💰", "💵", "💸")
        add("kerja kantor", "💼", "🏢", "💻")
        add("komputer laptop ngoding", "💻", "⌨️", "🖥️")
        add("hp telepon ponsel", "📱", "☎️")
        add("foto kamera", "📷", "📸")
        add("musik lagu nyanyi", "🎵", "🎶", "🎤")
        add("bola sepakbola", "⚽", "🥅")
        add("game gim", "🎮", "🕹️")
        add("ulangtahun ultah", "🎂", "🎉", "🎁")
        add("selamat sukses", "🎉", "🥳", "👏")
        add("hadiah", "🎁", "🎀")
        add("doa berdoa sholat salat terimakasih makasih", "🙏", "🤲")
        add("sakit demam", "🤒", "😷", "🤕")
        add("obat", "💊", "🩹")
        add("api kebakaran", "🔥", "🚒")
        add("buku belajar", "📚", "📖", "✏️")
        add("waktu jam", "⏰", "⌚")
        add("setuju oke mantap bagus", "👍", "👌", "✅")
        add("peluk", "🤗", "🫂")
        add("ciuman cium", "😘", "💋")
        add("pantai laut", "🏖️", "🌊")
        add("gunung", "⛰️", "🏔️")
    }
    private val word = Regex("[\\p{L}]+")
    fun suggest(before: String): List<String> {
        val sentence = before.takeLast(120).substringAfterLast('\n')
            .split(Regex("[.!?]")).last().lowercase(Locale.ROOT)
        val tokens = word.findAll(sentence).map { it.value }.toList()
        if (tokens.takeLast(2) == listOf("ulang", "tahun")) return listOf("🎂", "🎉", "🎁")
        if (tokens.takeLast(2) == listOf("terima", "kasih")) return listOf("🙏", "🤲", "😊")
        return tokens.takeLast(8).asReversed().firstNotNullOfOrNull { entries[it] }.orEmpty()
    }
}
