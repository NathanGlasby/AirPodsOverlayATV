plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Keep build outputs out of OneDrive — its sync locks files mid-build.
layout.buildDirectory.set(file("C:/GradleBuilds/AirPodsOverlayATV/app"))

android {
    namespace = "dev.nathan.airpodstv"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.nathan.airpodstv"
        minSdk = 28
        targetSdk = 34
        versionCode = 12
        versionName = "2.2"
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
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
