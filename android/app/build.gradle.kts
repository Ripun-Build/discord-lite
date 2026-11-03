plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
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
        // Real phones (64- and 32-bit ARM) plus 64-bit emulators. Old 32-bit x86 emulators are dropped.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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
        // libsignal uses newer Java APIs; desugaring makes them work back to Android 9.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // libsignal's JVM jar also carries desktop native libraries; phones use the .so files
            // from libsignal-android instead, so keep the desktop ones out of the APK.
            excludes += listOf("libsignal_jni*.dylib", "libsignal_jni*.so", "signal_jni*.dll")
        }
        jniLibs {
            // libsignal ships a separate testing build of its native library; the app never loads it.
            excludes += listOf("**/libsignal_jni_testing.so")
            // Store native libraries compressed (they're extracted on install). libsignal's library
            // includes debug symbols and compresses very well; release builds will be split per CPU
            // type by the Play Store's App Bundle anyway.
            useLegacyPackaging = true
        }
    }
}

// A real phone plugged in over USB reaches the dev server at its own 127.0.0.1:8080, which only
// works once adb forwards that port to this Mac. Do it after every debug build, so pressing Run in
// Android Studio is enough. The emulator uses 10.0.2.2 and doesn't need it.
val usbTunnel = tasks.register("usbTunnel") {
    val adb = androidComponents.sdkComponents.adb
    doLast {
        val exe = adb.get().asFile.absolutePath
        val phones = runCatching {
            ProcessBuilder(exe, "devices").start().inputStream.bufferedReader().readLines()
                .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 && it[1] == "device" }?.get(0) }
                .filterNot { it.startsWith("emulator-") }
        }.getOrDefault(emptyList())
        phones.forEach { serial ->
            val ok = runCatching {
                ProcessBuilder(exe, "-s", serial, "reverse", "tcp:8080", "tcp:8080")
                    .redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
            logger.lifecycle(if (ok) "USB tunnel ready on $serial" else "Couldn't open the USB tunnel on $serial")
        }
    }
}
tasks.configureEach {
    if (name == "assembleDebug" || name == "installDebug") finalizedBy(usbTunnel)
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
    implementation(libs.libsignal.android)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.compose.ui.tooling)
}
