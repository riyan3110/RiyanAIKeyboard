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
        val normalizedSegment = normalize(segment)
        val seed = stableSeed(tail)

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
            if (cleaned.isBlank() || !isSafeMemoryValue(cleaned)) return
            if (results.any { normalize(it.text) == normalize(cleaned) }) return
            results += KeyboardSuggestion(
                matchCase(cleaned, if (replaceLength == segment.length) segment else token),
                replaceLength
            )
        }

        // When the user has just pressed space, predict the next word/phrase from personal and
        // built-in phrase patterns. It looks at up to the last four words, so suggestions can
        // appear before the next character is typed.
        if (tail.lastOrNull()?.isWhitespace() == true) {
            val contextWords = normalizedWords(segment.trimEnd()).takeLast(8)
            if (contextWords.isNotEmpty()) {
                val continuations = allPhrases
                    .mapNotNull { entry ->
                        continuationAfterContext(entry.text, contextWords)?.let { continuation ->
                            Triple(continuation, entry.uses, entry.lastUsedAt)
                        }
                    }
                    .distinctBy { normalize(it.first) }
                    .sortedWith(
                        compareByDescending<Triple<String, Int, Long>> { it.second }
                            .thenByDescending { it.third }
                    )
                    .take(10)
                rotateStable(continuations, seed).forEach { (continuation, _, _) ->
                    add(continuation, 0)
                }
            }
            if (results.size >= safeLimit) return results.take(safeLimit)
        }

        if (normalizedToken.length >= 2) {
            // Complete the whole current phrase first when it matches a known phrase.
            if (normalizedSegment.length >= 2) {
                val matchingPhrases = allPhrases
                    .filter {
                        normalize(it.text).startsWith(normalizedSegment) &&
                            normalize(it.text) != normalizedSegment
                    }
                    .take(10)
                rotateStable(matchingPhrases, seed).forEach { add(it.text, segment.length) }
            }

            // Personal completions are preferred, but rotate among the best matches so the first
            // suggestion is not permanently the same every time the same prefix is used.
            val matchingPersonal = personal
                .filter {
                    normalize(it.text).startsWith(normalizedToken) &&
                        normalize(it.text) != normalizedToken
                }
                .take(10)
            rotateStable(matchingPersonal, seed xor 0x5F3759DF).forEach { add(it.text, token.length) }

            (words.asSequence() + phrases.asSequence())
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

        // Empty/one-character state: show a rotating pair from useful personal memory instead of
        // pinning the same highest-frequency phrase forever. Built-in phrases fill an empty memory.
        val idlePool = personal.take(10).map { it.text } + phrases.take(10)
        rotateStable(idlePool.distinctBy(::normalize), seed).forEach { add(it, 0) }
        return results.take(safeLimit)
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

    private fun continuationAfterContext(phrase: String, contextWords: List<String>): String? {
        val phraseWords = normalizedWords(phrase)
        if (phraseWords.size < 2 || contextWords.isEmpty()) return null
        val maxContext = minOf(4, contextWords.size, phraseWords.size - 1)
        for (size in maxContext downTo 1) {
            val suffix = contextWords.takeLast(size)
            for (start in 0..phraseWords.size - size) {
                if (phraseWords.subList(start, start + size) == suffix && start + size < phraseWords.size) {
                    return phraseWords.drop(start + size).take(7).joinToString(" ")
                }
            }
        }
        return null
    }

    private fun <T> rotateStable(values: List<T>, seed: Int): List<T> {
        if (values.size <= 1) return values
        val offset = Math.floorMod(seed, values.size)
        if (offset == 0) return values
        return values.drop(offset) + values.take(offset)
    }

    private fun stableSeed(context: String): Int {
        val normalized = normalize(context.takeLast(120))
        val timeBucket = (System.currentTimeMillis() / 60_000L).toInt()
        return normalized.hashCode() xor timeBucket
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
