package com.riyan.aikeyboard

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

    @Test fun shiftTapAndLongPressCapsKeepTheSameKeyRows() {
        val controller = Robolectric.buildService(RiyanKeyboardService::class.java).create()
        val service = controller.get()
        service.onCreateInputView()

        fun field(name: String) = RiyanKeyboardService::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
        fun method(name: String) = RiyanKeyboardService::class.java
            .getDeclaredMethod(name)
            .apply { isAccessible = true }

        val panel = field("keyboardPanel").get(service) as LinearLayout
        val rows = (0 until panel.childCount).map(panel::getChildAt)
        assertTrue(rows.isNotEmpty())

        fun assertRowsUnchanged() {
            assertEquals(rows.size, panel.childCount)
            rows.forEachIndexed { index, row -> assertSame(row, panel.getChildAt(index)) }
        }

        val shiftTap = method("handleShiftTap")
        val shiftHold = method("handleShiftHold")

        // One tap is one-shot Shift. It must only refresh labels, never replace rows.
        shiftTap.invoke(service)
        assertTrue(field("shift").getBoolean(service))
        assertFalse(field("capsLock").getBoolean(service))
        assertRowsUnchanged()

        // A second tap simply turns the one-shot Shift off; double-tap Caps Lock was removed.
        shiftTap.invoke(service)
        assertFalse(field("shift").getBoolean(service))
        assertFalse(field("capsLock").getBoolean(service))
        assertRowsUnchanged()

        // Caps Lock is now deliberately activated by holding Shift.
        shiftHold.invoke(service)
        assertTrue(field("capsLock").getBoolean(service))
        assertTrue(field("shift").getBoolean(service))
        assertRowsUnchanged()

        // Tapping Shift while locked unlocks Caps Lock without rebuilding the keyboard rows.
        shiftTap.invoke(service)
        assertFalse(field("capsLock").getBoolean(service))
        assertFalse(field("shift").getBoolean(service))
        assertRowsUnchanged()

        // Do not force Robolectric service destruction here. The keyboard owns
        // Android camera/IME lifecycle objects whose shadow teardown can throw
        // after the Shift assertions have already passed, unrelated to this test.
    }

    @Test fun longBookQueryRetainsLastWords() {
        val query = "Buku matematika kelas sepuluh dengan sampul biru dan judul terbaca, ".repeat(15) + "edisi revisi lengkap"
        val raw = org.json.JSONObject().put("subject_type", "text").put("confidence", 0.95)
            .put("query", query).put("evidence", "Sampul buku matematika biru, judul dan kelas terbaca").toString()
        val normalize = AiClient::class.java.getDeclaredMethod("normalizeVisionResult", String::class.java).apply { isAccessible = true }
        assertEquals(query, normalize.invoke(AiClient, raw))
    }
}
