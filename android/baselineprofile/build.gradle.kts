// Generates the app's baseline and startup profiles and measures startup and frame timing on a device or emulator.
//   ./gradlew :app:generateReleaseBaselineProfile      writes app/src/release/generated/baselineProfiles/
//   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   startup and frame benchmarks
// Needs a connected device or emulator (API 28+; generation needs API 33+ or a rooted device).
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "opensec.pibolt.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
