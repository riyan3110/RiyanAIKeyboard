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
}
