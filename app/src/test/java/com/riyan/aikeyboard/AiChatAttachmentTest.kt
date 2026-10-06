package com.riyan.aikeyboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiChatAttachmentTest {
    @Test fun validPhotoBecomesReadableAttachmentDespiteNullBoundsDecode() {
        val service = Robolectric.buildService(RiyanKeyboardService::class.java).get()
        val source = File(service.cacheDir, "gallery-photo.png")
        val photo = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(photo.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        photo.recycle()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assertNull(BitmapFactory.decodeFile(source.path, bounds))
        assertEquals(2000, bounds.outWidth)

        val attach = RiyanKeyboardService::class.java.getDeclaredMethod("attachAiChatImage", Uri::class.java)
        attach.isAccessible = true
        attach.invoke(service, Uri.fromFile(source))
        val imageField = RiyanKeyboardService::class.java.getDeclaredField("aiChatImageUri")
        imageField.isAccessible = true
        val attached = imageField.get(service) as? Uri
        assertNotNull("Gallery selection must populate the image sent to AI", attached)
        val decoded = BitmapFactory.decodeFile(attached!!.path)
        assertNotNull(decoded)
        assertEquals(1600, decoded.width)
        assertEquals(800, decoded.height)
        decoded.recycle()
    }
    @Test fun previewHasSpaceAboveInputAndClearingRestoresAnswerSpace() {
        val service = Robolectric.buildService(RiyanKeyboardService::class.java).get()
        val area = android.widget.FrameLayout(service)
        val card = android.widget.LinearLayout(service)
        val row = android.widget.LinearLayout(service)
        val thumb = android.widget.ImageView(service)
        val answer = android.widget.ScrollView(service)
        area.layoutParams = android.widget.LinearLayout.LayoutParams(300, 71)
        area.addView(card, android.widget.FrameLayout.LayoutParams(300, 71))
        area.addView(row, android.widget.FrameLayout.LayoutParams(50, 26))
        answer.layoutParams = android.widget.LinearLayout.LayoutParams(300, 70)
        fun field(name: String, value: Any?) {
            RiyanKeyboardService::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(service, value)
            }
        }
        field("aiComposeAreaView", area)
        field("aiComposeCardView", card)
        field("aiChatAttachmentRow", row)
        field("aiChatAttachmentThumb", thumb)
        field("aiAnswerScroll", answer)
        val render = RiyanKeyboardService::class.java.getDeclaredMethod("renderAiAttachment").apply {
            isAccessible = true
        }
        render.invoke(service)
        val originalHeight = area.layoutParams.height + answer.layoutParams.height
        field("aiChatImageUri", Uri.fromFile(File(service.cacheDir, "preview.jpg")))
        render.invoke(service)
        assertEquals(android.view.View.VISIBLE, row.visibility)
        assertEquals(0f, row.translationY, 0f)
        assertTrue((card.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin > 0)
        assertEquals(originalHeight, area.layoutParams.height + answer.layoutParams.height)
        field("aiChatImageUri", null)
        render.invoke(service)
        assertEquals(android.view.View.GONE, row.visibility)
        assertEquals(0, (card.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin)
        assertEquals(originalHeight, area.layoutParams.height + answer.layoutParams.height)
    }

}
