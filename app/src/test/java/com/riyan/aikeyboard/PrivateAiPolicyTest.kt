package com.riyan.aikeyboard

import org.junit.Assert.*
import org.junit.Test

class PrivateAiPolicyTest {
    @Test fun quotedNewsDoesNotTurnWritingIntoResearch() {
        assertTrue(PrivateAiPolicy.isWritingRequest("Tolong buatkan komentar: berita terbaru hari ini"))
        assertTrue(PrivateAiPolicy.isWritingRequest("Terjemahkan: latest news today"))
        assertTrue(PrivateAiPolicy.isWritingRequest("Balas pesan ini: harga terbaru berapa?"))
    }
    @Test fun factualQuestionStillUsesNormalChat() {
        assertFalse(PrivateAiPolicy.isWritingRequest("Berita terbaru hari ini apa?"))
        assertFalse(PrivateAiPolicy.isWritingRequest("Apa arti kata komentar?"))
    }
    @Test fun longProductDescriptionKeepsItsEndingAndConnectives() {
        val description = "A bottle with a blue cap and a white label, ".repeat(12) + "wearing a complete label"
        val result = VisionSearchEvidence.refineQuery(description, "Example Racing oil 10W-40 1L")
        assertTrue(result.contains(description))
    }
}
