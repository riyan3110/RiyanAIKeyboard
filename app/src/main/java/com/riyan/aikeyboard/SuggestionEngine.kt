package com.riyan.aikeyboard

import java.util.Locale

data class LearnedSuggestion(
    val text: String,
    val uses: Int = 1,
    val lastUsedAt: Long = 0L
)

data class KeyboardSuggestion(
    val text: String,
    val replaceLength: Int
)

/**
 * Local-only completion and typo correction. It does not contact an API and does not inspect
 * fields other than the editor currently connected to the keyboard.
 *
 * Personal memory is filtered before it is stored or shown. Useful items such as e-mail
 * addresses, phone numbers, names, addresses, and commonly used phrases are allowed, while
 * OTP/PIN/CVV values, likely card numbers, credentials, URLs, and noisy values are rejected.
 */
object SuggestionEngine {
    private val words = listOf(
        "ada", "adalah", "agar", "akan", "aku", "alamat", "aman", "anda", "apa", "apakah",
        "atau", "baik", "bagaimana", "banyak", "baru", "belum", "benar", "berikut", "bersama",
        "bisa", "boleh", "buat", "cara", "cukup", "dalam", "dan", "dari", "datang", "dengan",
        "dimana", "email", "gimana", "hari", "harus", "halo", "ingin", "ini", "itu", "iya",
        "jadi", "jangan", "jika", "juga", "kabari", "kalau", "kami", "kamu", "kapan", "karena",
        "kasih", "kembali", "kenapa", "kerja", "lagi", "langsung", "masih", "mau", "mohon",
        "mungkin", "nama", "nanti", "nggak", "oke", "pada", "pagi", "perlu", "pasti", "pesan",
        "sama", "sampai", "saya", "sekarang", "selamat", "selesai", "semoga", "silakan", "siang",
        "sore", "sudah", "terima", "tersebut", "tidak", "tolong", "untuk", "waktu", "yang",
        "akhir", "ambil", "antar", "aplikasi", "atas", "bagus", "balas", "bantu", "bayar", "beberapa",
        "beda", "berangkat", "berita", "bertemu", "besok", "biar", "buka", "bulan", "cepat", "coba",
        "contoh", "dapat", "dekat", "dulu", "ganti", "gratis", "hasil", "hapus", "harga", "hubungi",
        "ikut", "ingat", "jalan", "jawab", "jelas", "kabar", "kecil", "keluar", "kirim", "kurang",
        "lama", "lihat", "lebih", "lengkap", "makan", "masalah", "masuk", "minta", "mudah", "mulai",
        "nomor", "orang", "pakai", "paling", "pembayaran", "pilih", "punya", "rencana", "rumah",
        "salah", "sebentar", "sedang", "segera", "semua", "sendiri", "sesuai", "setelah", "siap",
        "simpan", "tadi", "tahu", "tanggal", "tempat", "tentang", "terakhir", "tetap", "tunggu",
        "ubah", "ulang", "update", "versi"
    )

    private val phrases = listOf(
        "apa kabar", "baik, saya mengerti", "belum selesai", "boleh minta tolong",
        "halo, apa kabar", "iya, tidak masalah", "nanti saya kabari", "sama-sama",
        "selamat malam", "selamat pagi", "selamat siang", "selamat sore", "sudah selesai",
        "terima kasih", "terima kasih banyak", "tidak apa-apa", "tolong kirim ke email saya",
        "baik, nanti saya kabari", "bisa dikirim sekarang", "boleh saya tanya", "coba kirim ulang",
        "maaf baru membalas", "mohon tunggu sebentar", "nanti saya cek lagi", "saya belum tahu",
        "saya kirim sekarang", "saya sudah terima", "semoga cepat selesai", "terima kasih atas bantuannya"
    )

    private val nextWordFallbacks = mapOf(
        "aku" to listOf("mau", "akan", "sudah", "bisa", "nggak"),
        "saya" to listOf("akan", "sudah", "belum", "bisa", "mau"),
        "kamu" to listOf("bisa", "sudah", "mau", "lagi", "juga"),
        "mau" to listOf("ke", "buat", "tanya", "pakai", "coba"),
        "akan" to listOf("saya", "aku", "dibuat", "dikirim", "selesai"),
        "sudah" to listOf("bisa", "selesai", "saya", "aku", "dikirim"),
        "belum" to listOf("bisa", "selesai", "ada", "saya", "tahu"),
        "bisa" to listOf("dikirim", "dibuat", "pakai", "langsung", "lebih"),
        "tidak" to listOf("bisa", "ada", "perlu", "masalah", "tahu"),
        "nggak" to listOf("bisa", "ada", "perlu", "masalah", "tahu"),
        "tolong" to listOf("kirim", "buat", "bantu", "cek", "ubah"),
        "coba" to listOf("cek", "lihat", "buat", "kirim", "ulang"),
        "buat" to listOf("yang", "jadi", "lebih", "seperti", "sama"),
        "yang" to listOf("ada", "sudah", "baru", "lain", "lebih"),
        "dan" to listOf("juga", "bisa", "tidak", "saya", "kalau"),
        "dengan" to listOf("yang", "cara", "baik", "benar", "aman"),
        "untuk" to listOf("saya", "kamu", "yang", "bisa", "membuat"),
        "di" to listOf("sini", "sana", "rumah", "aplikasi", "bagian"),
        "ke" to listOf("sini", "sana", "rumah", "aplikasi", "dalam"),
        "dari" to listOf("sini", "sana", "aplikasi", "awal", "yang"),
        "kalau" to listOf("bisa", "sudah", "belum", "ada", "mau"),
        "karena" to listOf("saya", "aku", "tidak", "nggak", "masih"),
        "biar" to listOf("bisa", "lebih", "nggak", "tidak", "aman"),
        "lebih" to listOf("baik", "cepat", "jelas", "aman", "mudah"),
        "terima" to listOf("kasih"),
        "selamat" to listOf("pagi", "siang", "sore", "malam"),
        "apa" to listOf("kabar", "bisa", "yang", "ada", "perlu"),
        "ini" to listOf("bisa", "sudah", "masih", "yang", "untuk"),
        "itu" to listOf("bisa", "sudah", "masih", "yang", "untuk")
    )

    private val sensitiveWords = Regex(
        "(?i)(^|\\b)(otp|pin|passcode|password|passwd|kata\\s+sandi|kode\\s+verifikasi|verification\\s+code|cvv|cvc)(\\b|$)"
    )
    private val urlRegex = Regex("(?i)^(https?://|www\\.)\\S+$")
    private val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val likelyCredential = Regex(
        "(?i)^(sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{20,}|AIza[A-Za-z0-9_-]{20,}|eyJ[A-Za-z0-9_-]{18,}\\.[A-Za-z0-9_-]{8,}.*)$"
    )

    fun suggest(
        beforeCursor: String,
        learned: List<LearnedSuggestion>,
        savedEntries: List<String>,
        limit: Int = 2
    ): List<KeyboardSuggestion> {
        val safeLimit = limit.coerceIn(1, 2)
        val tail = beforeCursor.takeLast(220)
        val segment = currentSegment(tail)
        val token = segment.takeLastWhile(::isSuggestionCharacter)
        val normalizedToken = normalize(token)
        val contextBeforeToken = normalizedWords(segment.dropLast(token.length).trimEnd())

        val learnedRank = learned
            .asSequence()
            .filter { isSafeMemoryValue(it.text) }
            .sortedWith(compareByDescending<LearnedSuggestion> { it.uses }.thenByDescending { it.lastUsedAt })
            .toList()
        val personal = (
            savedEntries.filter(::isSafeMemoryValue).map { LearnedSuggestion(it, Int.MAX_VALUE / 4) } + learnedRank
        )
            .filter { it.text.isNotBlank() }
            .distinctBy { normalize(it.text) }
        val allPhrases = personal + phrases.map { LearnedSuggestion(it) }
        val results = mutableListOf<KeyboardSuggestion>()

        fun add(text: String, replaceLength: Int) {
            val cleaned = text.trim().replace(Regex("\\s+"), " ")
            if (!isVisibleSuggestion(cleaned)) return
            if (results.any { normalize(it.text) == normalize(cleaned) }) return
            results += KeyboardSuggestion(
                matchCase(cleaned, token),
                replaceLength
            )
        }

        // After a space, predict exactly one next word. Personal sequences are ranked by actual
        // usage and recency, then the compact Indonesian fallback model fills any empty slot.
        if (tail.lastOrNull()?.isWhitespace() == true) {
            val contextWords = normalizedWords(segment.trimEnd()).takeLast(8)
            if (contextWords.isNotEmpty()) {
                val continuations = allPhrases
                    .mapNotNull { entry ->
                        nextWordAfterContext(entry.text, contextWords)?.let { continuation ->
                            Triple(continuation, entry.uses, entry.lastUsedAt)
                        }
                    }
                    .distinctBy { normalize(it.first) }
                    .sortedWith(
                        compareByDescending<Triple<String, Int, Long>> { it.second }
                            .thenByDescending { it.third }
                    )
                continuations.forEach { (continuation, _, _) ->
                    add(continuation, 0)
                }
                fallbackNextWords(contextWords).forEach { add(it, 0) }
            }
            return results.take(safeLimit)
        }

        if (normalizedToken.length >= 2) {
            // Complete only the current word, even when the match came from a learned sentence.
            // This prevents a long sentence from occupying the compact prediction bar.
            personal.mapNotNull { entry ->
                completionAfterContext(entry.text, contextBeforeToken, normalizedToken)?.let {
                    Triple(it, entry.uses, entry.lastUsedAt)
                }
            }.distinctBy { normalize(it.first) }
                .sortedWith(
                    compareByDescending<Triple<String, Int, Long>> { it.second }
                        .thenByDescending { it.third }
                )
                .forEach { (completion, _, _) -> add(completion, token.length) }

            phrases.asSequence()
                .mapNotNull { completionAfterContext(it, contextBeforeToken, normalizedToken) }
                .forEach { add(it, token.length) }

            personal.asSequence()
                .mapNotNull { standaloneCompletion(it.text, normalizedToken) }
                .forEach { add(it, token.length) }

            words.asSequence()
                .filter { normalize(it).startsWith(normalizedToken) && normalize(it) != normalizedToken }
                .forEach { add(it, token.length) }

            // Typo correction is kept as a fallback; at most two total items are shown by the UI.
            if (!token.contains('@') && normalizedToken.length >= 3 && token.any(Char::isLetter)) {
                words.asSequence()
                    .map { candidate -> candidate to editDistance(normalizedToken, candidate, 2) }
                    .filter { (candidate, distance) ->
                        candidate != normalizedToken && distance in 1..allowedDistance(normalizedToken.length)
                    }
                    .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })
                    .forEach { (candidate, _) -> add(candidate, token.length) }
            }
            return results.take(safeLimit)
        }

        // An empty editor or a single typed character is too ambiguous. Keep the permanent bar
        // empty instead of showing unrelated saved phrases.
        return emptyList()
    }

    fun learnableEntries(beforeCursor: String): List<String> {
        val tail = beforeCursor.takeLast(180).trimEnd()
        if (tail.isBlank()) return emptyList()

        val token = tail.takeLastWhile(::isSuggestionCharacter).trim()
        val sentence = currentSegment(tail).trim().replace(Regex("\\s+"), " ")

        return buildList {
            when {
                isEmail(token) -> add(token)
                isPhoneNumber(token) -> add(token)
                token.length in 3..80 && token.any(Char::isLetter) && isSafeMemoryValue(token) -> add(token)
            }

            val wordCount = sentence.split(' ').count { it.isNotBlank() }
            if (
                wordCount in 2..14 &&
                sentence.length in 7..140 &&
                isSafeMemoryValue(sentence) &&
                sentence.any(Char::isLetter)
            ) {
                add(sentence)
            }
        }
            .filter(::isSafeMemoryValue)
            .distinctBy(::normalize)
    }

    fun isSafeMemoryValue(value: String): Boolean {
        val cleaned = value.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length !in 2..140) return false
        if (sensitiveWords.containsMatchIn(cleaned)) return false
        if (urlRegex.matches(cleaned)) return false
        if (likelyCredential.matches(cleaned)) return false

        val digits = cleaned.filter(Char::isDigit)
        val compact = cleaned.replace(Regex("[\\s().+-]"), "")

        // A standalone short numeric code is much more likely to be OTP/PIN/CVV than useful memory.
        if (compact.all(Char::isDigit) && digits.length in 3..6) return false

        // Reject likely payment-card values even when users typed spaces or dashes.
        if (digits.length in 13..19 && compact.all(Char::isDigit) && passesLuhn(digits)) return false

        // Long random-looking credentials should never be put back in the prediction bar.
        if (
            !cleaned.contains(' ') &&
            cleaned.length >= 24 &&
            cleaned.count(Char::isDigit) >= 4 &&
            cleaned.count(Char::isLetter) >= 8 &&
            !isEmail(cleaned)
        ) return false

        return true
    }

    private fun nextWordAfterContext(phrase: String, contextWords: List<String>): String? {
        val phraseWords = normalizedWords(phrase)
        if (phraseWords.size < 2 || contextWords.isEmpty()) return null
        val maxContext = minOf(4, contextWords.size, phraseWords.size - 1)
        for (size in maxContext downTo 1) {
            val suffix = contextWords.takeLast(size)
            for (start in 0..phraseWords.size - size) {
                if (phraseWords.subList(start, start + size) == suffix && start + size < phraseWords.size) {
                    return phraseWords[start + size]
                }
            }
        }
        return null
    }

    private fun completionAfterContext(phrase: String, contextWords: List<String>, prefix: String): String? {
        val phraseWords = normalizedWords(phrase)
        if (phraseWords.isEmpty()) return null
        val maxContext = minOf(4, contextWords.size, phraseWords.size - 1)
        for (size in maxContext downTo 1) {
            val suffix = contextWords.takeLast(size)
            for (start in 0..phraseWords.size - size) {
                val nextIndex = start + size
                if (
                    phraseWords.subList(start, nextIndex) == suffix &&
                    nextIndex < phraseWords.size &&
                    phraseWords[nextIndex].startsWith(prefix) &&
                    phraseWords[nextIndex] != prefix
                ) return phraseWords[nextIndex]
            }
        }
        return if (contextWords.isEmpty()) standaloneCompletion(phrase, prefix) else null
    }

    private fun standaloneCompletion(value: String, prefix: String): String? {
        val cleaned = value.trim().replace(Regex("\\s+"), " ")
        val first = cleaned.substringBefore(' ')
        return first.takeIf {
            normalize(it).startsWith(prefix) && normalize(it) != prefix
        }
    }

    private fun fallbackNextWords(contextWords: List<String>): List<String> {
        if (contextWords.isEmpty()) return emptyList()
        val twoWordKey = contextWords.takeLast(2).joinToString(" ")
        return nextWordFallbacks[twoWordKey].orEmpty() + nextWordFallbacks[contextWords.last()].orEmpty()
    }

    private fun isVisibleSuggestion(value: String): Boolean {
        val cleaned = value.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank() || cleaned.contains(' ') || cleaned.length > MAX_VISIBLE_SUGGESTION_CHARS) return false
        return isSafeMemoryValue(cleaned)
    }

    private fun normalizedWords(value: String): List<String> =
        normalize(value)
            .split(' ')
            .map { it.trim(' ', ',', ':', ';', '"', '\'', '(', ')') }
            .filter { it.isNotBlank() }

    private fun isEmail(value: String): Boolean = emailRegex.matches(value.trim())

    private fun isPhoneNumber(value: String): Boolean {
        val cleaned = value.trim()
        val digits = cleaned.filter(Char::isDigit)
        if (digits.length !in 7..15) return false
        if (!cleaned.all { it.isDigit() || it in "+-(). " }) return false
        if (digits.length in 13..15 && passesLuhn(digits)) return false
        return true
    }

    private fun passesLuhn(digits: String): Boolean {
        if (digits.length < 13) return false
        var sum = 0
        var doubleDigit = false
        for (index in digits.indices.reversed()) {
            var n = digits[index].digitToInt()
            if (doubleDigit) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            doubleDigit = !doubleDigit
        }
        return sum % 10 == 0
    }

    private fun currentSegment(text: String): String {
        val boundary = text.indexOfLast { it == '\n' || it == '.' || it == '!' || it == '?' }
        return text.substring(boundary + 1).trimStart().takeLast(140)
    }

    private fun isSuggestionCharacter(char: Char): Boolean =
        char.isLetterOrDigit() || char in "@._+-'()"

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun matchCase(value: String, typed: String): String =
        if (typed.firstOrNull()?.isUpperCase() == true) value.replaceFirstChar { it.uppercase() } else value

    private fun allowedDistance(length: Int): Int = if (length >= 4) 2 else 1

    private const val MAX_VISIBLE_SUGGESTION_CHARS = 32

    private fun editDistance(left: String, right: String, stopAfter: Int): Int {
        if (kotlin.math.abs(left.length - right.length) > stopAfter) return stopAfter + 1
        var previous = IntArray(right.length + 1) { it }
        for (leftIndex in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            var rowMinimum = current[0]
            for (rightIndex in right.indices) {
                val replaceCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + replaceCost
                )
                rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
            }
            if (rowMinimum > stopAfter) return stopAfter + 1
            previous = current
        }
        return previous[right.length]
    }
}
