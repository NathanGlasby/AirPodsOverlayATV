plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

providers.gradleProperty("externalBuildDir").orNull?.let {
    layout.buildDirectory.set(file("$it/app"))
}

val releaseStorePath = providers.environmentVariable("AIRPODS_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("AIRPODS_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("AIRPODS_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("AIRPODS_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "dev.nathan.airpodstv"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.nathan.airpodstv"
        minSdk = 28
        targetSdk = 34
        versionCode = 17
        versionName = "2.7"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(checkNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.register("verifyReleaseSigning") {
    group = "verification"
    doLast {
        check(hasReleaseSigning) {
            "Release signing requires AIRPODS_KEYSTORE_PATH, AIRPODS_KEYSTORE_PASSWORD, " +
                "AIRPODS_KEY_ALIAS, and AIRPODS_KEY_PASSWORD."
        }
    }
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    testImplementation("junit:junit:4.13.2")
}
