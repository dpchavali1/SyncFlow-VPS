import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
    id("com.google.devtools.ksp")
    // Firebase plugins removed - using VPS backend only
}

android {
    namespace = "com.phoneintegration.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phoneintegration.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Production BuildConfig fields
        buildConfigField("String", "SUPPORT_EMAIL", "\"syncflow.contact@gmail.com\"")
        buildConfigField("String", "SUPPORT_EMAIL_SUBJECT", "\"[SyncFlow Android] Support Request\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"https://syncflow.app/privacy\"")
        buildConfigField("String", "TERMS_OF_SERVICE_URL", "\"https://syncflow.app/terms\"")
    }

    signingConfigs {
        // Load local.properties
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }

        create("release") {
            storeFile = file(localProperties.getProperty("KEYSTORE_FILE"))
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
            keyAlias = localProperties.getProperty("KEY_ALIAS")
            keyPassword = localProperties.getProperty("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Uncomment when signing config is set up:
            signingConfig = signingConfigs.getByName("release")

            // Disable debugging for release
            isDebuggable = false

            // BuildConfig for release
            buildConfigField("Boolean", "ENABLE_LOGGING", "false")
            buildConfigField("Boolean", "ENABLE_CRASH_REPORTING", "true") // Using CustomCrashReporter (VPS backend)
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            // Note: Don't use applicationIdSuffix - it would require updating google-services.json
            versionNameSuffix = "-debug"

            buildConfigField("Boolean", "ENABLE_LOGGING", "true")
            buildConfigField("Boolean", "ENABLE_CRASH_REPORTING", "true") // Using CustomCrashReporter for testing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
        }
    }

    // Lint configuration for production
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

// Fix duplicate annotations issue by excluding old IntelliJ annotations
configurations.all {
    exclude(group = "com.intellij", module = "annotations")
}

dependencies {

    // ──────────────────────────────────────────────
    // CORE AND LIFECYCLE
    // ──────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")



    // Additional AndroidX dependencies
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ──────────────────────────────────────────────
    // COMPOSE — SINGLE VERSION (BOM 2024-10)
    // ──────────────────────────────────────────────
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")   // for SwipeToDismiss
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("com.google.android.gms:play-services-ads:23.2.0")
    implementation("androidx.room:room-compiler:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.work:work-runtime-ktx:2.9.0")


    // ──────────────────────────────────────────────
    // NAVIGATION — MUST MATCH COMPOSE VERSION
    // ──────────────────────────────────────────────
    implementation("androidx.navigation:navigation-compose:2.8.2")

    // ──────────────────────────────────────────────
    // OTHER LIBRARIES
    // ──────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")


    implementation("com.google.mlkit:smart-reply:17.0.3")
    implementation("com.google.android.gms:play-services-base:18.3.0")
    implementation("com.google.android.material:material:1.11.0")

    // Firebase Functions for other features (not AI)

    // ──────────────────────────────────────────────
    // LOCAL AI - pattern matching for SMS analysis
    // ──────────────────────────────────────────────
    // No external dependencies needed - using local pattern matching

    // ──────────────────────────────────────────────
    // TENSORFLOW LITE - for on-device spam detection
    // ──────────────────────────────────────────────
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("io.coil-kt:coil-compose:2.4.0")
    implementation("io.coil-kt:coil-gif:2.4.0")  // GIF support for Coil
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ──────────────────────────────────────────────
    // VPS BACKEND - Desktop Integration (Firebase removed)
    // ──────────────────────────────────────────────
    // All sync, auth, and messaging now handled via VPS server

    // Certificate pinning dependencies
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Encrypted SharedPreferences for VPS token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.code.gson:gson:2.10.1")

    // QR Code generation for pairing
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // ──────────────────────────────────────────────
    // MMS SENDING - Klinker library for reliable MMS
    // ──────────────────────────────────────────────
    implementation("com.github.klinker41:android-smsmms:5.2.5")

    // ──────────────────────────────────────────────
    // WEBRTC - for call audio routing to desktop
    // ──────────────────────────────────────────────
    implementation("io.getstream:stream-webrtc-android:1.1.3")

    // ──────────────────────────────────────────────
    // E2EE - for secure messaging (using standard crypto)
    // ──────────────────────────────────────────────
    // Using Android's built-in cryptography + Tink for key exchange
    implementation("com.google.crypto.tink:tink-android:1.12.0")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")

    // ──────────────────────────────────────────────
    // ROOM DATABASE - for Groups
    // ──────────────────────────────────────────────
    val room_version = "2.8.4"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")

// Instrumented tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

}
