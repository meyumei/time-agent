plugins {
    // Versions are declared apply-false by the root project (AGP 8.9.2 / Kotlin 2.1.20).
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.timeagent.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // The pinned proot build and rootfs only exist for arm64.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Keep the pinned .bin/rootfs assets byte-identical inside the APK. openFd() and
    // the SHA-256 check in RuntimeLock both rely on the stored bytes, not aapt output.
    androidResources { noCompress += listOf("bin", "gz") }

    // libproot.so is exec'd from nativeLibraryDir, so the APK must extract JNI libs.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
