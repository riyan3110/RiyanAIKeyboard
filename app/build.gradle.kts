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
        versionCode = 20
        versionName = "0.20.0"
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

tasks.named("preBuild").configure {
    dependsOn(patchDynamicImeAction)
    dependsOn(patchBluesMindsProvider)
}