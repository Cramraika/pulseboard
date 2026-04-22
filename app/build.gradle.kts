plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cramraika.pulseboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cramraika.pulseboard"
        minSdk = 26
        targetSdk = 36
        // Stub placeholder — real engine lands in v1.1 alongside :core VoIP diagnostic work.
        versionCode = 1
        versionName = "0.1.0-stub"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Pulseboard uses its own keystore — distinct from app-cn so the two
            // distribution chains can't collide. Paths still come from Gradle properties
            // (keystore.properties / local.properties), never committed.
            storeFile = file(
                project.findProperty("PULSEBOARD_KEYSTORE_PATH") as String? ?: "unset"
            )
            storePassword = project.findProperty("PULSEBOARD_KEYSTORE_PASSWORD") as String? ?: ""
            keyAlias = project.findProperty("PULSEBOARD_KEY_ALIAS") as String? ?: ""
            keyPassword = project.findProperty("PULSEBOARD_KEY_PASSWORD") as String? ?: ""
        }
    }

    buildTypes {
        release {
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
    // Shared engine — available but not wired into a service yet.
    // v1.1 fills in the public-facing PingService + configurable target onboarding.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
