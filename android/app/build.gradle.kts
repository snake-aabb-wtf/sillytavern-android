plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionCodeEnv = (System.getenv("VERSION_CODE") ?: "1000").toInt()
val runtimeVersion = System.getenv("RUNTIME_VERSION") ?: "dev"

android {
    namespace = "com.sol.sillytavern"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sol.sillytavern"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeEnv
        versionName = "1.0.$versionCodeEnv"

        ndk {
            abiFilters += "arm64-v8a"
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
}

dependencies {
}
