plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.wearbridge.watch"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.wearbridge.watch"
        minSdk = 30 // Wear OS 3. ANCS solicitation needs API 31+, and the app falls back without it.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-poc"
    }

    buildTypes {
        release {
            // R8 + resource shrinking: Compose on a Watch4-class chip is markedly smoother optimized,
            // and non-debuggable builds use the libraries' ahead-of-time (baseline) profiles.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Local installs only; a store build would use an upload key.
            signingConfig = signingConfigs.getByName("debug")
        }
        // Release-optimized but with the debug demo tools (fake data for screenshots and frame timing).
        create("profiling") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }

    sourceSets {
        getByName("profiling") {
            kotlin.directories += "src/debug/kotlin"
            manifest.srcFile("src/debug/AndroidManifest.xml")
            res.directories += "src/debug/res"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":ancs"))
    implementation(project(":protocol"))
    implementation(project(":ams"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)

    // Tile (swipe from the watch face): Material 3 Expressive tile layouts.
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.concurrent.futures.ktx)

    testImplementation(libs.junit)
}
