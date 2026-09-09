package com.riyan.aikeyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One explicitly started utterance. All callbacks and lifecycle operations stay on main. */
internal class PrivateVoiceCapture(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onResult: (String) -> Unit
) : RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var closed = false
    private var finishing = false
    private val timeout = Runnable { fail("Waktu habis. Ketuk mic untuk mencoba lagi.") }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            fail("Layanan pengenal suara belum tersedia. Aktifkan layanan suara perangkat.")
            return
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
                it.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    // Give the recognizer a little more time to hear the complete sentence.
                    // Some engines ignore these hints, but supported engines become less likely to cut off the last word.
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                })
            }
            handler.postDelayed(timeout, 30000)
        } catch (_: Exception) { fail("Mic belum bisa digunakan. Periksa izin mikrofon dan layanan suara.") }
    }

    fun finish() {
        if (closed || finishing) return
        finishing = true
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 5000)
        try { recognizer?.stopListening() }
        catch (_: Exception) { fail("Suara belum terbaca. Silakan coba lagi.") }
    }

    fun cancel() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        val old = recognizer
        recognizer = null
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
    }

    private fun fail(message: String) {
        if (closed) return
        cancel()
        onError(message)
    }

    override fun onResults(results: Bundle?) {
        if (closed) return
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        val bestIndex = if (confidences != null && confidences.size >= matches.size && matches.isNotEmpty()) {
            matches.indices.maxByOrNull { confidences[it] } ?: 0
        } else 0
        val text = matches.getOrNull(bestIndex)
            ?.takeIf { it.isNotBlank() }
            ?.trim()
            .orEmpty()
            .take(2000)
        if (text.isBlank()) {
            fail("Tidak ada ucapan yang terbaca. Ketuk mic dan coba lagi.")
            return
        }
        cancel()
        onResult(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!closed) partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
    }

    override fun onError(error: Int) = fail(when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan. Ketuk mic untuk mengizinkan."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ucapan belum jelas. Dekatkan mic dan coba lagi."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Koneksi pengenal suara bermasalah. Periksa internet lalu coba lagi."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Layanan suara sedang sibuk. Coba lagi sebentar."
        else -> "Suara gagal dibaca. Ketuk mic untuk mencoba lagi."
    })

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        if (closed) return
        // The recognizer has already stopped collecting audio. Do not call stopListening() again here;
        // just wait briefly for its final/high-confidence transcription.
        finishing = true
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 5000)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
