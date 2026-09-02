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
        versionCode = 9
        versionName = "0.9.0"
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
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
