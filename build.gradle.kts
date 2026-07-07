plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// Keep build outputs out of OneDrive — its sync locks files mid-build.
// Windows-only: elsewhere "C:/..." resolves as a relative path and litters the
// repo with a literal "C:" directory.
if (System.getProperty("os.name").startsWith("Windows")) {
    layout.buildDirectory.set(file("C:/GradleBuilds/AirPodsOverlayATV/root"))
}
