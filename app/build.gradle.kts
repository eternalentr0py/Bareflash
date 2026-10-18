plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.bareflash.app"
    compileSdk = 36
    // Pinned so CI and local builds use the same NDK instead of whatever
    // happens to be installed. The release workflow caches this exact version.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.bareflash.app"
        minSdk = 26
        targetSdk = 34
        // CI overrides these (-PversionName / -PversionCode) so every release
        // has a unique, increasing versionCode.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                // 16 KB page alignment for Android 15+ devices.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    // Builds the small JNI bridge to libusb (app/src/main/cpp/usb_native.c)
    // used by LibusbNative.kt / UsbBridge.kt as the USB transfer path.
    // Adds real time to CI builds (NDK/CMake compilation) -- worth knowing
    // given the separate CI-speed conversation, but not something to trade
    // off here.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // libusb-1.0.so lives in src/main/jniLibs AND is an IMPORTED cmake
    // dependency of libmtkusb.so. AGP would otherwise fail the native-libs
    // merge with "2 files found with path 'lib/<abi>/libusb-1.0.so'".
    // pickFirsts is also what keeps a stripped cmake copy from replacing
    // the jniLibs build that usb_native.c actually dlopens via
    // System.loadLibrary("usb-1.0") -- without an explicit load, some
    // Android versions fail to resolve mtkusb's DT_NEEDED at runtime
    // (LIBUSB_ERROR_ACCESS / wrap_sys_device fail, no bulk I/O).
    packaging {
        jniLibs {
            pickFirsts += "**/libusb-1.0.so"
        }
    }

    signingConfigs {
        // A debug key that is committed to the repo and reused by every
        // build — CI and local builds alike. Without this, AGP falls back
        // to ~/.android/debug.keystore, which is generated per-machine; a
        // GitHub Actions runner is a fresh machine every run, so each CI
        // build was silently signed with a different key, and Android
        // refuses to install an APK over an existing install signed with
        // a different key ("app not installed" / signature mismatch).
        // This keystore is for debug builds only — never do this for a
        // release signing config.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "bareflashdebug"
            keyAlias = "bareflashdebug"
            keyPassword = "bareflashdebug"
        }
    }

    // Optional release signing: set these environment variables (CI passes
    // them from repository secrets) to sign with your own key. Without them
    // the release build falls back to the shared debug key, which is fine for
    // sideloading but should not be used for anything you distribute widely.
    val releaseKeystore = System.getenv("BAREFLASH_KEYSTORE_FILE")
    if (releaseKeystore != null && file(releaseKeystore).exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("BAREFLASH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BAREFLASH_KEY_ALIAS")
                keyPassword = System.getenv("BAREFLASH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Pure-logic tests only. Android framework calls (Log, etc.) return
        // defaults instead of throwing, so a stray log line cannot fail a test.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        // Do not pin a Termux interpreter path — Android Studio / Gradle
        // auto-detect python3 on the build machine.
        pip {
            install("pycryptodome")
            install("pycryptodomex")
            install("colorama")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    // Jetpack Compose + Material 3. The Expressive theme APIs are public only
    // on the material3 1.5.0-alpha line (1.4.0 stable keeps them internal), so
    // material3 is pinned explicitly; the BOM supplies the remaining artifacts.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    // Native ADB / fastboot protocol code. Pure Kotlin/JVM modules; :fastboot exposes
    // :usbcore (the transport interface) through api(), so both are visible here.
    implementation(project(":fastboot"))

    // JVM unit tests (app/src/test). Run with: ./gradlew testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}

// The pure-Kotlin protocol modules (:usbcore, :fastboot) have their own JVM
// tests. Running them from the existing testDebugUnitTest keeps the Tests
// workflow (./gradlew testDebugUnitTest) covering them without a CI change.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(":usbcore:test", ":fastboot:test")
}
