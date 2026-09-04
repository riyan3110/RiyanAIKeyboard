from pathlib import Path

SERVICE = Path("app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
source = SERVICE.read_text()


def replace_once(old: str, new: str) -> None:
    global source
    if new in source:
        return
    if old not in source:
        raise RuntimeError(f"Gallery lifecycle patch marker not found:\n{old[:220]}")
    source = source.replace(old, new, 1)


replace_once(
    "    private var lastConsumedScanNonce = 0L\n",
    "    private var lastConsumedScanNonce = 0L\n"
    "    private var galleryLaunchInProgress = false\n"
    "    private var lastGalleryLaunchAt = 0L\n"
    "    private var galleryResultReceiverRegistered = false\n"
    "    private val galleryResultReceiver = object : android.content.BroadcastReceiver() {\n"
    "        override fun onReceive(context: android.content.Context?, intent: Intent?) {\n"
    "            if (intent?.action != GALLERY_RESULT_ACTION) return\n"
    "            galleryLaunchInProgress = false\n"
    "            getSharedPreferences(PREFS, MODE_PRIVATE).edit()\n"
    "                .putBoolean(GALLERY_SESSION_ACTIVE_KEY, false)\n"
    "                .remove(GALLERY_SESSION_STARTED_KEY)\n"
    "                .apply()\n"
    "            handler.postDelayed({\n"
    "                requestShowSelf(0)\n"
    "                handler.postDelayed({ consumePendingScanResult() }, 120L)\n"
    "            }, GALLERY_RESTORE_DELAY_MS)\n"
    "        }\n"
    "    }\n"
)

replace_once(
    "    override fun onCreate() {\n"
    "        super.onCreate()\n"
    "        clipboardManager.addPrimaryClipChangedListener(clipboardListener)\n"
    "    }\n",
    "    override fun onCreate() {\n"
    "        super.onCreate()\n"
    "        clipboardManager.addPrimaryClipChangedListener(clipboardListener)\n"
    "        registerGalleryResultReceiver()\n"
    "    }\n"
)

replace_once(
    "    override fun onDestroy() {\n"
    "        clipboardManager.removePrimaryClipChangedListener(clipboardListener)\n",
    "    override fun onDestroy() {\n"
    "        clipboardManager.removePrimaryClipChangedListener(clipboardListener)\n"
    "        if (galleryResultReceiverRegistered) {\n"
    "            runCatching { unregisterReceiver(galleryResultReceiver) }\n"
    "            galleryResultReceiverRegistered = false\n"
    "        }\n"
)

replace_once(
    "        if (prefs.getBoolean(GALLERY_READY_KEY, false)) {\n"
    "            val galleryUriText = prefs.getString(GALLERY_URI_KEY, \"\").orEmpty().trim()\n"
    "            prefs.edit().putBoolean(GALLERY_READY_KEY, false).apply()\n",
    "        if (prefs.getBoolean(GALLERY_READY_KEY, false)) {\n"
    "            galleryLaunchInProgress = false\n"
    "            val galleryUriText = prefs.getString(GALLERY_URI_KEY, \"\").orEmpty().trim()\n"
    "            prefs.edit()\n"
    "                .putBoolean(GALLERY_READY_KEY, false)\n"
    "                .putBoolean(GALLERY_SESSION_ACTIVE_KEY, false)\n"
    "                .remove(GALLERY_SESSION_STARTED_KEY)\n"
    "                .apply()\n"
)

old_launch = '''    private fun launchGalleryPicker() {
        scannerStatusText?.text = "Pilih gambar dari galeri…"
        runCatching {
            startActivity(
                Intent(this, GalleryPickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }.onFailure {
            scannerStatusText?.text = "Galeri tidak dapat dibuka."
            Toast.makeText(this, "Galeri tidak dapat dibuka.", Toast.LENGTH_SHORT).show()
        }
    }
'''

new_launch = '''    private fun registerGalleryResultReceiver() {
        if (galleryResultReceiverRegistered) return
        val filter = android.content.IntentFilter(GALLERY_RESULT_ACTION)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(galleryResultReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(galleryResultReceiver, filter)
            }
            galleryResultReceiverRegistered = true
        }
    }

    private fun launchGalleryPicker() {
        val now = SystemClock.elapsedRealtime()
        if (galleryLaunchInProgress || now - lastGalleryLaunchAt < GALLERY_LAUNCH_DEBOUNCE_MS) return

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val sessionActive = prefs.getBoolean(GALLERY_SESSION_ACTIVE_KEY, false)
        val sessionStartedAt = prefs.getLong(GALLERY_SESSION_STARTED_KEY, 0L)
        val sessionFresh = sessionActive &&
            System.currentTimeMillis() - sessionStartedAt in 0..GALLERY_SESSION_TIMEOUT_MS
        if (sessionFresh) return

        galleryLaunchInProgress = true
        lastGalleryLaunchAt = now
        prefs.edit()
            .putBoolean(GALLERY_SESSION_ACTIVE_KEY, true)
            .putLong(GALLERY_SESSION_STARTED_KEY, System.currentTimeMillis())
            .apply()
        scannerStatusText?.text = "Pilih satu foto dari galeri…"

        runCatching {
            startActivity(
                Intent(this, GalleryPickerActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
            )
        }.onFailure {
            galleryLaunchInProgress = false
            prefs.edit()
                .putBoolean(GALLERY_SESSION_ACTIVE_KEY, false)
                .remove(GALLERY_SESSION_STARTED_KEY)
                .apply()
            scannerStatusText?.text = "Galeri tidak dapat dibuka."
            Toast.makeText(this, "Galeri tidak dapat dibuka.", Toast.LENGTH_SHORT).show()
        }
    }
'''
replace_once(old_launch, new_launch)

replace_once(
    "        private const val GALLERY_READY_KEY = \"camera_gallery_ready\"\n"
    "        private const val GALLERY_URI_KEY = \"camera_gallery_uri\"\n",
    "        private const val GALLERY_READY_KEY = \"camera_gallery_ready\"\n"
    "        private const val GALLERY_URI_KEY = \"camera_gallery_uri\"\n"
    "        private const val GALLERY_RESULT_ACTION = \"com.riyan.aikeyboard.GALLERY_RESULT\"\n"
    "        private const val GALLERY_SESSION_ACTIVE_KEY = \"camera_gallery_session_active\"\n"
    "        private const val GALLERY_SESSION_STARTED_KEY = \"camera_gallery_session_started\"\n"
    "        private const val GALLERY_LAUNCH_DEBOUNCE_MS = 1_500L\n"
    "        private const val GALLERY_SESSION_TIMEOUT_MS = 120_000L\n"
    "        private const val GALLERY_RESTORE_DELAY_MS = 320L\n"
)

SERVICE.write_text(source)
print("Applied gallery lifecycle guard and keyboard restore patch")
