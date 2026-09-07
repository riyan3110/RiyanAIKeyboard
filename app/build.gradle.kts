plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.riyan.aikeyboard"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.riyan.aikeyboard"
        minSdk = 23
        targetSdk = 35
        versionCode = 29
        versionName = "0.21.8-test-smart-product-vision"
    }

    signingConfigs {
        getByName("debug") {
            System.getenv("RIYAN_KEYSTORE_PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let { customKeystore ->
                    storeFile = file(customKeystore)
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.android.gms:play-services-mlkit-image-labeling:16.0.8")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:object-detection:17.0.2")
}

// Keep the IME action key synchronized with the field opened by the host app.
// Some apps (X, browsers, stores, etc.) expose IME_ACTION_SEARCH/SEND/NEXT/DONE,
// so the keyboard should show the matching action and perform it instead of inserting a newline.
val patchDynamicImeAction by tasks.registering {
    doLast {
        val sourceFile = file("src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
        var source = sourceFile.readText()

        source = source.replace(
            """KeySpec("↵", weight = 1.25f, action = { pressEnter() })""",
            """KeySpec(enterKeyLabel(), weight = 1.25f, action = { pressEnter() })"""
        )

        source = source.replace(
            """spec.label in listOf("⇧", "⇪", "⌫", "↵", "◀", "▶")""",
            """spec.label in listOf("⇧", "⇪", "⌫", "↵", "🔍", "➤", "→", "←", "✓", "◀", "▶")"""
        )

        if (!source.contains("private fun enterKeyLabel()")) {
            val marker = "    private fun pressEnter() {\n"
            val helper = """
    private fun enterKeyLabel(): String {
        if (aiComposeActive) return "➤"
        return when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_NEXT -> "→"
            EditorInfo.IME_ACTION_PREVIOUS -> "←"
            EditorInfo.IME_ACTION_DONE -> "✓"
            EditorInfo.IME_ACTION_GO -> "→"
            else -> "↵"
        }
    }

"""
            source = source.replace(marker, helper + marker)
        }

        source = source.replace(
            """        if (enterActionEnabled && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }""",
            """        val semanticActions = setOf(
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS
        )
        if (action in semanticActions || (enterActionEnabled && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }"""
        )

        sourceFile.writeText(source)
    }
}

// BluesMinds was previously used by AI Ads Lab. Apply the same OpenAI-compatible
// provider wiring before compilation without changing unrelated keyboard behavior.
val patchBluesMindsProvider by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("tools/apply_bluesminds_provider_patch.py").absolutePath)
}

// Settings used to live inside the IME, so its EditTexts intentionally blocked the
// Android soft keyboard. On the new separate SettingsActivity they must behave like
// normal fields. Also make the photo theme as easy as the old settings: tapping the
// photo option immediately opens the device gallery/file picker.
val patchSettingsInputAndGallery by tasks.registering {
    doLast {
        val sourceFile = file("src/main/java/com/riyan/aikeyboard/KeyboardSettingsOverlay.kt")
        var source = sourceFile.readText()

        val oldSoftInput = "showSoftInputOnFocus = false"
        val newSoftInput = "showSoftInputOnFocus = context is android.app.Activity"
        when {
            source.contains(oldSoftInput) -> source = source.replace(oldSoftInput, newSoftInput)
            source.contains(newSoftInput) -> Unit
            else -> error("Settings soft-input patch did not match the source")
        }

        val oldPhotoClick = """                    setOnClickListener {
                        draft.themeMode = mode
                        renderBody()
                    }"""
        val newPhotoClick = """                    setOnClickListener {
                        draft.themeMode = mode
                        if (mode == KeyboardTheme.MODE_PHOTO) {
                            val pickerIntent = Intent(context, ThemePhotoPickerActivity::class.java)
                            if (context !is android.app.Activity) {
                                pickerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(pickerIntent)
                        } else {
                            renderBody()
                        }
                    }"""
        when {
            source.contains(oldPhotoClick) -> source = source.replace(oldPhotoClick, newPhotoClick)
            source.contains(newPhotoClick) -> Unit
            else -> error("Photo-theme one-tap patch did not match the source")
        }

        sourceFile.writeText(source)
    }
}

// Only apply the three requested visual changes: AI header branding and centered
// alphanumeric key legends. This intentionally leaves all existing keyboard behavior untouched.
val patchReferenceBrandingAndKeyCentering by tasks.registering {
    doLast {
        val sourceFile = file("src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
        var source = sourceFile.readText()

        val oldHeader = """
header.addView(TextView(this).apply {
    text = "✨ Obrolan AI"
    textSize = if (isLandscape()) 15f else 18f
    setTextColor(Color.rgb(43, 40, 50))
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(9), 0, dp(10), 0)
    typeface = aiBoldTypeface
    background = roundedBackground(purple, 8f)
}, LinearLayout.LayoutParams(-2, headerControlHeight))
header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
""".trimIndent().prependIndent("        ")

        val newHeader = """
header.addView(ImageView(this).apply {
    setImageResource(R.drawable.ai_ads_keyboard_header)
    scaleType = ImageView.ScaleType.FIT_CENTER
    adjustViewBounds = true
    contentDescription = "AI Ads Keyboard"
}, LinearLayout.LayoutParams(0, headerControlHeight, 1f).apply {
    rightMargin = dp(4)
})
""".trimIndent().prependIndent("        ")

        when {
            source.contains("R.drawable.ai_ads_keyboard_header") -> Unit
            source.contains(oldHeader) -> source = source.replace(oldHeader, newHeader)
            else -> error("AI conversation header patch did not match the source")
        }

        val oldLegendOffset = "if (spec.alternate != null) translationY = dpFloat(4f)"
        val centeredLegendOffset =
            "if (spec.alternate != null && spec.label.none { it.isLetterOrDigit() }) translationY = dpFloat(4f)"

        when {
            source.contains(centeredLegendOffset) -> Unit
            source.contains(oldLegendOffset) -> source = source.replace(oldLegendOffset, centeredLegendOffset)
            else -> error("Keyboard legend centering patch did not match the source")
        }

        sourceFile.writeText(source)
    }
}

// Keep the version text shown on the keyboard synchronized with this test build.
val patchVisibleVersionLabel by tasks.registering {
    doLast {
        val sourceFile = file("src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt")
        var source = sourceFile.readText()
        val hardcoded = """text = "AI Ads Keyboard · v0.20""""
        val previous = """text = "AI Ads Keyboard · v0.21.6 test""""
        val visible = """text = "AI Ads Keyboard · v0.21.7 test""""

        when {
            source.contains(hardcoded) -> source = source.replace(hardcoded, visible)
            source.contains(previous) -> source = source.replace(previous, visible)
            source.contains(visible) -> Unit
            else -> error("Visible keyboard version label patch did not match the source")
        }

        sourceFile.writeText(source)
    }
}

patchSettingsInputAndGallery.configure {
    mustRunAfter(patchBluesMindsProvider)
}

patchReferenceBrandingAndKeyCentering.configure {
    mustRunAfter(patchDynamicImeAction)
    mustRunAfter(patchBluesMindsProvider)
}

patchVisibleVersionLabel.configure {
    mustRunAfter(patchDynamicImeAction)
    mustRunAfter(patchBluesMindsProvider)
    mustRunAfter(patchSettingsInputAndGallery)
    mustRunAfter(patchReferenceBrandingAndKeyCentering)
}

tasks.named("preBuild").configure {
    dependsOn(patchDynamicImeAction)
    dependsOn(patchBluesMindsProvider)
    dependsOn(patchSettingsInputAndGallery)
    dependsOn(patchReferenceBrandingAndKeyCentering)
    dependsOn(patchVisibleVersionLabel)
}

apply(from = "smart-vision.gradle.kts")
