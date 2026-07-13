plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// Local Windows builds can opt out of OneDrive with -PexternalBuildDir=C:/GradleBuilds/AirPodsOverlayATV.
providers.gradleProperty("externalBuildDir").orNull?.let {
    layout.buildDirectory.set(file("$it/root"))
}
