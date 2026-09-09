package com.riyan.aikeyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/** Microphone mode remains selected after recognition ends, until explicitly disabled. */
class PublicVoiceSearch(private val context: Context, private val changed: (String) -> Unit,
    private val submit: (String) -> Unit) {
    var active = false
        private set
    private var recognizer: SpeechRecognizer? = null
    private var transcript = ""
    private var listening = false
    private var pendingSearch = false
    private var session = 0

    fun toggle() {
        if (active) { release(); changed("Mode kamera/foto · tekan Cari"); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent(context, PublicMicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            changed("Izinkan mikrofon, lalu ketuk mic lagi")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            changed("Pengenalan suara belum tersedia di perangkat"); return
        }
        active = true
        transcript = ""
        pendingSearch = false
        listening = true
        val token = ++session
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { if (token == session) changed("Mic aktif · ucapkan pencarian") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { if (token == session) changed("Menyelesaikan teks suara…") }
                    override fun onError(error: Int) {
                        if (token != session) return
                        listening = false; pendingSearch = false
                        changed("Suara belum berhasil dikenali ($error) · matikan lalu aktifkan mic untuk mengulang")
                    }
                    override fun onResults(results: Bundle?) {
                        if (token != session) return
                        listening = false
                        transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                        changed(if (transcript.isBlank()) "Suara kosong · ulangi mic" else "Mic: $transcript")
                        if (pendingSearch) { pendingSearch = false; search() }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (token == session) partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { changed("Mic: $it") }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
            }
        } catch (_: Exception) {
            release(); changed("Mic gagal dimulai · coba lagi")
        }
    }
    fun search() {
        if (!active) return
        if (listening) {
            pendingSearch = true
            recognizer?.stopListening()
            changed("Menunggu teks akhir suara…")
        } else if (transcript.isNotBlank()) submit(transcript)
        else changed("Belum ada teks suara · ulangi mic")
    }
    fun release() {
        ++session
        active = false; listening = false; pendingSearch = false; transcript = ""
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
    }
}
