package com.riyan.aikeyboard

import android.os.SystemClock
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class PublicAiShiftTest {
    @Test fun writingAboutNewsDoesNotBecomeNewsLookup() {
        assertEquals("Balas", PublicAiPolicy.writingAction("Tolong buatkan komentar: berita terbaru hari ini"))
        assertEquals("Terjemah", PublicAiPolicy.writingAction("Terjemahkan: latest news today"))
        assertEquals("Ringkas", PublicAiPolicy.writingAction("Ringkas artikel harga terbaru ini"))
        assertNull(PublicAiPolicy.writingAction("Berita terbaru hari ini apa?"))
        assertNull(PublicAiPolicy.writingAction("Apa arti komentar?"))
    }

    @Test fun fastDoubleShiftLocksCapsWithoutReplacingAnyKeyRows() {
        val controller = Robolectric.buildService(RiyanKeyboardService::class.java).create()
        val service = controller.get()
        service.onCreateInputView()
        fun field(name: String) = RiyanKeyboardService::class.java.getDeclaredField(name).apply { isAccessible = true }
        val panel = field("keyboardPanel").get(service) as LinearLayout
        val rows = (0 until panel.childCount).map(panel::getChildAt)
        assertTrue(rows.isNotEmpty())
        val shift = RiyanKeyboardService::class.java.getDeclaredMethod("handleShiftTap").apply { isAccessible = true }
        SystemClock.sleep(10)
        shift.invoke(service)
        SystemClock.sleep(30)
        shift.invoke(service)
        assertTrue(field("capsLock").getBoolean(service))
        assertTrue(field("shift").getBoolean(service))
        assertEquals(rows.size, panel.childCount)
        rows.forEachIndexed { i, row -> assertSame(row, panel.getChildAt(i)) }
        shift.invoke(service)
        assertFalse(field("capsLock").getBoolean(service))
        controller.destroy()
    }

    @Test fun longBookQueryRetainsLastWords() {
        val query = "Buku matematika kelas sepuluh dengan sampul biru dan judul terbaca, ".repeat(15) + "edisi revisi lengkap"
        val raw = org.json.JSONObject().put("subject_type", "text").put("confidence", 0.95)
            .put("query", query).put("evidence", "Sampul buku matematika biru, judul dan kelas terbaca").toString()
        val normalize = AiClient::class.java.getDeclaredMethod("normalizeVisionResult", String::class.java).apply { isAccessible = true }
        assertEquals(query, normalize.invoke(AiClient, raw))
    }
}
