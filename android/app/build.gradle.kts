plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.replymint"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.replymint"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Physical device over USB: `adb reverse tcp:8787 tcp:8787` tunnels the phone's
            // 127.0.0.1:8787 to the host machine's backend. Same command also works on the
            // emulator. (Emulator-only alternative if you skip adb reverse: http://10.0.2.2:8787.)
            buildConfigField("String", "BASE_URL", "\"http://127.0.0.1:8787\"")
            isMinifyEnabled = false
        }
        release {
            buildConfigField("String", "BASE_URL", "\"https://api.replymint.app\"")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}
