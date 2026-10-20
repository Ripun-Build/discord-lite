plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.discordlite.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.discordlite.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes.configureEach {
        // Emulator reaches the Mac's localhost at 10.0.2.2. For a real phone on Wi-Fi, run with
        // -PapiBaseUrl=http://<your-mac-ip>:8080/
        val debugUrl = providers.gradleProperty("apiBaseUrl").getOrElse("http://10.0.2.2:8080/")
        val url = if (name == "release") "https://api.discord-lite.app/" else debugUrl
        buildConfigField("String", "API_BASE_URL", "\"$url\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.tink.android)

    debugImplementation(libs.compose.ui.tooling)
}
