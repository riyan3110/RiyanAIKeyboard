package com.riyan.aikeyboard

import org.junit.Assert.*
import org.junit.Test

class PublicSearchTextTest {
    @Test fun speechCannotChangeMeaningOrNumbers() {
        assertEquals("bukan samsung 25", PublicSearchText.voiceQuery("bukan samsung 25", "Samsung 26"))
        assertEquals("cari buku ipa", PublicSearchText.voiceQuery("cari buku ipa", "Buku IPA tersedia di toko"))
    }
    @Test fun speechAllowsFormattingAndFallsBackOnProviderFailure() {
        assertEquals("Cari buku IPA.", PublicSearchText.voiceQuery("cari buku ipa", "Cari buku IPA."))
        assertEquals("harga buku", PublicSearchText.voiceQuery("harga buku", ""))
    }
    @Test fun keepsCompleteTitleAndDetails() {
        val query = "Buku Ilmu Pengetahuan Alam untuk SMP kelas VIII edisi revisi sampul hijau bergambar tumbuhan"
        assertEquals(query, PublicSearchText.visualQuery("Query: $query"))
    }
    @Test fun rejectsRunawayRepetitionWithoutRemovingLegitimateRepeatedWords() {
        assertEquals("", PublicSearchText.visualQuery("buku sampul hijau buku sampul hijau buku sampul hijau buku sampul hijau"))
        assertEquals("Buku Kupu-kupu dan Hewan-hewan", PublicSearchText.visualQuery("Buku Kupu-kupu dan Hewan-hewan"))
    }
}
