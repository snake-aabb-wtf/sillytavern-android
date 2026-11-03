plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0+ requires the Compose Compiler Gradle plugin when compose is enabled;
    // it is versioned in lockstep with Kotlin, so the two can never disagree.
    id("org.jetbrains.kotlin.plugin.compose")
    // Required by the @Serializable models the native API client will use.
    id("org.jetbrains.kotlin.plugin.serialization")
}

val versionCodeEnv = (System.getenv("VERSION_CODE") ?: "1000").toInt()
// CI sets VERSION_NAME ("1.0.<run_number>" for branch builds, the tag for releases);
// the fallback keeps local builds installable.
val versionNameEnv = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: "1.0.$versionCodeEnv"
val runtimeVersion = System.getenv("RUNTIME_VERSION") ?: "dev"

android {
    namespace = "com.sol.sillytavern"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.sol.sillytavern"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeEnv
        versionName = versionNameEnv

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // libnode.so is prebuilt against libc++_shared.so.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

        buildConfigField("String", "RUNTIME_VERSION", "\"$runtimeVersion\"")
        buildConfigField("int", "APP_VERSION_CODE", "$versionCodeEnv")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // The BOM pins every androidx.compose.* artifact; 2024.10.01 is the Compose 1.7.x
    // train, which builds against compileSdk 35 exactly as this module does.
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Not part of the Compose BOM; both lines are pinned to the train that matches
    // Compose 1.7.x (navigation 2.8) and lifecycle 2.8.7 used above.
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // OkHttp 4.12.0 is the last 4.x line: it still ships the Android-friendly API the
    // SSE reader in task 5 targets, without the 5.x alpha/RT guarantees.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // JVM-only unit tests drive the engine client against a real HTTP socket;
    // MockWebServer needs no Android runtime, unlike Robolectric.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
