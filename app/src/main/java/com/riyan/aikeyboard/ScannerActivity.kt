package com.riyan.aikeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScannerActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var searchButton: Button

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val processingFrame = AtomicBoolean(false)
    private val barcodeScanner = BarcodeScanning.getClient()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.62f)
            .build()
    )

    private var bestScore = 0
    private var selectedQuery = ""
    private var selectedUrl = ""
    private var lastFrameAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = translucentPanel()
        }
        topBar.addView(TextView(this).apply {
            text = "Kamera Penelusuran"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        statusText = TextView(this).apply {
            text = "Arahkan kamera ke QR, barcode, tulisan, atau produk."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, 0)
        }
        topBar.addView(statusText)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(12), dp(12), dp(12), 0)
        })

        val target = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dpFloat(22f)
                setStroke(dp(3), Color.WHITE)
            }
        }
        root.addView(target, FrameLayout.LayoutParams(-1, dp(230), Gravity.CENTER).apply {
            setMargins(dp(25), 0, dp(25), 0)
        })

        val bottomCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = translucentPanel()
        }
        resultText = TextView(this).apply {
            text = "Belum ada hasil terdeteksi."
            textSize = 14f
            maxLines = 3
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        bottomCard.addView(resultText, ViewGroup.LayoutParams(-1, -2))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(actionButton("Batal", false) { finish() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            rightMargin = dp(5)
        })
        searchButton = actionButton("Cari hasil", true) { finishWithResult() }.apply { isEnabled = false }
        actions.addView(searchButton, LinearLayout.LayoutParams(0, dp(48), 1.35f).apply {
            leftMargin = dp(5)
        })
        bottomCard.addView(actions)
        root.addView(bottomCard, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(16))
        })
        setContentView(root)
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (primary) Color.rgb(104, 70, 205) else Color.rgb(65, 64, 72))
            cornerRadius = dpFloat(14f)
        }
        setOnClickListener { action() }
    }

    private fun translucentPanel() = GradientDrawable().apply {
        setColor(Color.argb(225, 28, 27, 34))
        cornerRadius = dpFloat(18f)
        setStroke(dp(1), Color.rgb(126, 87, 185))
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analyzerExecutor, ::analyzeFrame) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                statusText.text = "Memindai QR, barcode, tulisan, dan produk…"
            }.onFailure {
                statusText.text = "Kamera tidak tersedia pada perangkat ini."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < FRAME_INTERVAL_MS || !processingFrame.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastFrameAt = now
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            finishFrame(imageProxy)
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (barcode != null) {
                    publishBarcode(barcode)
                    finishFrame(imageProxy)
                } else {
                    analyzeTextAndLabels(input, imageProxy)
                }
            }
            .addOnFailureListener { analyzeTextAndLabels(input, imageProxy) }
    }

    private fun analyzeTextAndLabels(input: InputImage, imageProxy: ImageProxy) {
        var remaining = 2
        var recognizedText = ""
        var labels = emptyList<String>()

        fun completeOne() {
            remaining -= 1
            if (remaining != 0) return
            publishVisualResult(recognizedText, labels)
            finishFrame(imageProxy)
        }

        textRecognizer.process(input).addOnCompleteListener { task ->
            if (task.isSuccessful) recognizedText = task.result?.text.orEmpty()
            completeOne()
        }
        imageLabeler.process(input).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                labels = task.result.orEmpty()
                    .sortedByDescending { it.confidence }
                    .map { it.text.trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                    .take(3)
            }
            completeOne()
        }
    }

    private fun publishBarcode(barcode: Barcode) {
        val raw = barcode.rawValue.orEmpty().trim()
        if (raw.isBlank()) return
        val directUrl = when {
            barcode.valueType == Barcode.TYPE_URL -> barcode.url?.url.orEmpty()
            isWebUrl(raw) -> raw
            else -> ""
        }
        val query = when {
            directUrl.isNotBlank() -> directUrl
            barcode.format == Barcode.FORMAT_EAN_8 ||
                barcode.format == Barcode.FORMAT_EAN_13 ||
                barcode.format == Barcode.FORMAT_UPC_A ||
                barcode.format == Barcode.FORMAT_UPC_E -> "produk barcode $raw"
            barcode.valueType == Barcode.TYPE_ISBN -> "ISBN $raw"
            else -> raw
        }
        setCandidate(query, directUrl, 100, if (directUrl.isNotBlank()) "Tautan terdeteksi" else "Barcode terdeteksi")
    }

    private fun publishVisualResult(rawText: String, labels: List<String>) {
        val textLines = rawText.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 3..80 && it.any { char -> char.isLetterOrDigit() } }
            .distinctBy { it.lowercase() }
            .take(3)
            .toList()
        val queryParts = (textLines.take(2) + labels.take(2))
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (queryParts.isEmpty()) return
        val score = textLines.size * 12 + labels.size * 5
        val source = when {
            textLines.isNotEmpty() && labels.isNotEmpty() -> "Tulisan dan produk terdeteksi"
            textLines.isNotEmpty() -> "Tulisan terdeteksi"
            else -> "Objek terdeteksi"
        }
        setCandidate(queryParts.joinToString(" ").take(180), "", score, source)
    }

    private fun setCandidate(query: String, url: String, score: Int, source: String) {
        if (query.isBlank() || score < bestScore) return
        bestScore = score
        selectedQuery = query
        selectedUrl = url
        runOnUiThread {
            statusText.text = "$source · tekan Cari hasil"
            resultText.text = query
            searchButton.isEnabled = true
        }
    }

    private fun finishFrame(imageProxy: ImageProxy) {
        imageProxy.close()
        processingFrame.set(false)
    }

    private fun finishWithResult() {
        if (selectedQuery.isBlank() && selectedUrl.isBlank()) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(SCAN_READY_KEY, true)
            .putLong(SCAN_NONCE_KEY, System.currentTimeMillis())
            .putString(SCAN_QUERY_KEY, selectedQuery)
            .putString(SCAN_URL_KEY, selectedUrl)
            .commit()
        Toast.makeText(this, "Membuka hasil di keyboard…", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun isWebUrl(value: String): Boolean = runCatching {
        Uri.parse(value).scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            statusText.text = "Izin kamera diperlukan untuk melakukan pemindaian."
            Toast.makeText(this, "Izinkan kamera untuk memakai fitur penelusuran.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        barcodeScanner.close()
        textRecognizer.close()
        imageLabeler.close()
        analyzerExecutor.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dpFloat(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val PREFS = "riyan_ai"
        private const val CAMERA_PERMISSION_REQUEST = 701
        private const val FRAME_INTERVAL_MS = 650L
        private const val SCAN_READY_KEY = "camera_search_ready"
        private const val SCAN_NONCE_KEY = "camera_search_nonce"
        private const val SCAN_QUERY_KEY = "camera_search_query"
        private const val SCAN_URL_KEY = "camera_search_url"
    }
}
