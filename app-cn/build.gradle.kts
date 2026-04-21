plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.codingninjas.networkmonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codingninjas.networkmonitor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(project.findProperty("KEYSTORE_PATH") as String? ?: "unset")
            storePassword = project.findProperty("KEYSTORE_PASSWORD") as String? ?: ""
            keyAlias = project.findProperty("KEY_ALIAS") as String? ?: ""
            keyPassword = project.findProperty("KEY_PASSWORD") as String? ?: ""
        }
    }

    buildTypes {
        release {
            // Intentionally disabled for internal side-loaded builds.
            // Preserves full class/method names in adb logcat and in Gson/OkHttp reflection.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Shared Pulseboard engine — PingEngine, SampleBuffer, MetricsCalculator,
    // SheetsUploader, NetworkUtils, Sample/NetworkMetrics/SheetPayload data classes.
    implementation(project(":core"))

    // AndroidX core
    implementation(libs.androidx.core.ktx)

    // UI stack (View-based, not Compose)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // Gson — used by PingService prefs round-trip and MainActivity JSON decode
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines — PingService samplers + flusher
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Tests (JVM)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Instrumented tests (reserved for future Espresso work)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
