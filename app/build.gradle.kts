plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val androidAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")

// Upload receiver, supplied at build time — the public source carries no
// server secret:
//   ./gradlew assembleDebug -PllieServerUrl=http://host:8770 -PllieServerToken=...
val llieServerUrl: String = (findProperty("llieServerUrl") as String?) ?: ""
val llieServerToken: String = (findProperty("llieServerToken") as String?) ?: ""

android {
    namespace = "org.llie.mobilebenchmark"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.llie.mobilebenchmark"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        // Host-triggered runs must present this exact extra; keeps a stray app
        // on the phone from starting a benchmark run by itself.
        buildConfigField("String", "RUN_TOKEN", "\"llie-run-7c4Q2p\"")
        // Upload receiver baked in at build time (empty in the public source).
        buildConfigField("String", "SERVER_URL", "\"$llieServerUrl\"")
        buildConfigField("String", "SERVER_TOKEN", "\"$llieServerToken\"")
        ndk {
            abiFilters += androidAbis
        }
    }

    buildFeatures {
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*androidAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}