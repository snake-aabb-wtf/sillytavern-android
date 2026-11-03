plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
