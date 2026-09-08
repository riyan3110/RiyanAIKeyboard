package com.riyan.aikeyboard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class WebImagePickerActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<WebImagePickerActivity>>()

    @After fun cleanUp() {
        controllers.forEach { it.get().finish(); it.destroy() }
    }

    private fun open(callback: ValueCallback<Array<Uri>>): WebImagePickerActivity {
        assertTrue(WebImagePickerActivity.launch(app, callback, arrayOf("image/png", "image/jpeg")))
        val bridge = shadowOf(app).nextStartedActivity
        assertNotNull(bridge)
        return Robolectric.buildActivity(WebImagePickerActivity::class.java, bridge)
            .create().start().resume().also { controllers += it }.get()
    }

    @Test fun devicePickerIncludesAllPhotoFormatsAndOnlyOneSelection() {
        val activity = open(ValueCallback { })
        val picker = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertEquals("image/*", picker.type)
        assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE))
        assertFalse(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
    }

    @Test fun duplicateRequestsDoNotStackOrCancelOriginalCallback() {
        val originalResults = mutableListOf<Array<Uri>?>()
        val original = ValueCallback<Array<Uri>> { originalResults += it }
        val activity = open(original)
        val picker = shadowOf(activity).nextStartedActivityForResult.intent
        // Robolectric records the initial picker in both the start-for-result queue and the
        // global started-activity queue. Clear the baseline before checking duplicate taps.
        while (shadowOf(app).nextStartedActivity != null) { }
        var duplicateCalls = 0
        WebImagePickerActivity.launch(app, original, null)
        WebImagePickerActivity.launch(app, ValueCallback {
            assertNull(it); duplicateCalls++
        }, null)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(1, duplicateCalls)
        assertTrue(originalResults.isEmpty())
        val photo = Uri.parse("content://photos/one")
        shadowOf(activity).receiveResult(picker, Activity.RESULT_OK, Intent().setData(photo))
        assertEquals(1, originalResults.size)
        assertArrayEquals(arrayOf(photo), originalResults.single())
        assertTrue(activity.isFinishing)
    }

    @Test fun multipleReturnedUrisDeliverExactlyOnePhoto() {
        var result: Array<Uri>? = null
        val activity = open(ValueCallback { result = it })
        val picker = shadowOf(activity).nextStartedActivityForResult.intent
        val first = Uri.parse("content://photos/one")
        val clip = ClipData.newRawUri("photo", first).apply {
            addItem(ClipData.Item(Uri.parse("content://photos/two")))
        }
        shadowOf(activity).receiveResult(picker, Activity.RESULT_OK, Intent().apply { clipData = clip })
        assertArrayEquals(arrayOf(first), result)
    }

    @Test fun cancellationCompletesOnceAndAllowsNextPicker() {
        var calls = 0
        val activity = open(ValueCallback { assertNull(it); calls++ })
        val picker = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(picker, Activity.RESULT_CANCELED, null)
        assertEquals(1, calls)
        val next = open(ValueCallback { })
        assertFalse(next.isFinishing)
        assertNotNull(shadowOf(next).nextStartedActivityForResult)
    }

    @Test fun throwingWebViewCallbackDoesNotLeaveBridgeOpen() {
        val activity = open(ValueCallback { throw IllegalStateException("WebView released") })
        val picker = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(picker, Activity.RESULT_CANCELED, null)
        assertTrue(activity.isFinishing)
        assertFalse(open(ValueCallback { }).isFinishing)
    }

    @Test fun failedLaunchConsumesCallbackAndReleasesRequest() {
        var calls = 0
        val unavailable = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertTrue(WebImagePickerActivity.launch(unavailable, ValueCallback {
            assertNull(it); calls++
        }, null))
        assertEquals(1, calls)
        assertFalse(open(ValueCallback { }).isFinishing)
    }
}
