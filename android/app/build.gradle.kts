import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing lives outside the repo: android/keystore.properties (gitignored) points at the
// keystore. See docs/RELEASE.md — losing that keystore means losing the app's Play identity.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.replymint"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.replymint"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        vectorDrawables { useSupportLibrary = true }

        // Web OAuth client ID (the ID-token audience) — set in android/gradle.properties.
        val webClientId = (project.findProperty("replymint.googleWebClientId") as? String).orEmpty()
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$webClientId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Defaults to the live backend so a debug install works untethered. For local
            // backend dev, add `replymint.debugBaseUrl=http://127.0.0.1:8787` to
            // android/gradle.properties (or -P on the command line) and run
            // `adb reverse tcp:8787 tcp:8787` to tunnel the phone's 127.0.0.1 to the host.
            val debugBaseUrl = (project.findProperty("replymint.debugBaseUrl") as? String)
                ?: "https://replymint-um33.onrender.com"
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
            isMinifyEnabled = false
        }
        release {
            buildConfigField("String", "BASE_URL", "\"https://replymint-um33.onrender.com\"")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Compile Kotlin interface method bodies to real JVM default methods instead of the
        // `DefaultImpls` + per-implementor bridge scheme. The bridge scheme is what silently
        // left VoiceInput.Listener.onFinal abstract at runtime (AbstractMethodError). Safe here:
        // minSdk 26 and D8 desugars default methods regardless.
        freeCompilerArgs += "-Xjvm-default=all"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Google Sign-In via Credential Manager (the non-deprecated path).
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Onboarding pager (dot indicator comes from the material TabLayout above).
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
