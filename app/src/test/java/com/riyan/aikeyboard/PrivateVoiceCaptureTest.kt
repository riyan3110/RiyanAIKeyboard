package com.riyan.aikeyboard

import android.os.Bundle
import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class PrivateVoiceCaptureTest {
    private val results = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private val partials = mutableListOf<String>()

    private fun capture() = PrivateVoiceCapture(
        RuntimeEnvironment.getApplication(),
        { partials += it },
        { errors += it },
        { results += it }
    )

    private fun bundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    @Test fun finalResultDeliveredExactlyOnce() {
        val c = capture()
        c.onPartialResults(bundle("harga buku"))
        c.onResults(bundle("harga buku matematika"))
        c.onResults(bundle("duplikat"))
        assertEquals(listOf("harga buku matematika"), results)
        assertEquals(listOf("harga buku"), partials)
    }

    @Test fun picksHighestConfidenceFinalAlternative() {
        val c = capture()
        val resultBundle = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("harga buku matematika", "cari buku matematika", "buku matematika")
            )
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.44f, 0.91f, 0.31f))
        }
        c.onResults(resultBundle)
        assertEquals(listOf("cari buku matematika"), results)
    }

    @Test fun cancelledSessionCannotSearchOrUpdatePanel() {
        val c = capture()
        c.cancel()
        c.onPartialResults(bundle("terlambat"))
        c.onResults(bundle("terlambat"))
        c.onError(SpeechRecognizer.ERROR_NETWORK)
        assertTrue(results.isEmpty())
        assertTrue(partials.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test fun emptyResultDoesNotSearch() {
        val c = capture()
        c.onResults(bundle(" "))
        assertTrue(results.isEmpty())
        assertEquals(1, errors.size)
    }
}
