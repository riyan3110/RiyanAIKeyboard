package com.riyan.aikeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateVoiceQueryTest {
    @Test fun keepsConservativePunctuationCorrection() {
        assertEquals("Harga buku matematika kelas 9?", PrivateVoiceQuery.choose("harga buku matematika kelas 9", "Harga buku matematika kelas 9?"))
    }
    @Test fun fallsBackForEmptyOrUnrelatedAnswer() {
        val source = "cari buku matematika kelas 9"
        for (result in listOf(null, "", "Berikut hasilnya:\nBuku pelajaran", "Cuaca hari ini cerah sekali")) {
            assertEquals(source, PrivateVoiceQuery.choose(source, result))
        }
    }
    @Test fun cannotChangeModelNumberOrYear() {
        assertEquals("harga iqoo z9 2026", PrivateVoiceQuery.choose("harga iqoo z9 2026", "harga iqoo z10 2026"))
    }
}
