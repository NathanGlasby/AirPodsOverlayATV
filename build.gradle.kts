plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// Keep build outputs out of OneDrive — its sync locks files mid-build.
layout.buildDirectory.set(file("C:/GradleBuilds/AirPodsOverlayATV/root"))
