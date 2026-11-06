plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

// Optional release signing for local builds, from the environment only. Without these four variables the release APK
// is built unsigned; CI always builds it unsigned and signs it with apksigner. Never commit a keystore.
val keystorePath: String? = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val keystorePassword: String? = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val keyAlias: String? = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val keyPassword: String? = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val canSign = listOf(keystorePath, keystorePassword, keyAlias, keyPassword).all { !it.isNullOrBlank() }

// CI passes the release tag's version (v1.2.3 -> 1.2.3). versionCode is 1.2.3 -> 10203.
val appVersion: String = providers.environmentVariable("PIBOLT_APP_VERSION").orNull?.removePrefix("android-")?.removePrefix("v") ?: "0.1.0"
val appVersionCode: Int = appVersion.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 } }
    .coerceAtLeast(1)

// The on-device engine pack (android/engine/build-pack.sh, CONTRACT.md): jniLibs/arm64-v8a and assets/engine. Added when
// built; without it the APK has no engine (remote control still works).
val enginePack = rootDir.resolve("engine/out")

android {
    namespace = "opensec.pibolt.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "in.opensec.pibolt"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        // The engine and its userland are linux-arm64 only.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets {
        getByName("main") {
            if (enginePack.resolve("jniLibs").isDirectory) jniLibs.srcDir(enginePack.resolve("jniLibs"))
            if (enginePack.resolve("assets").isDirectory) assets.srcDir(enginePack.resolve("assets"))
        }
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (canSign) signingConfigs.getByName("release") else null
        }
        // No applicationIdSuffix for debug: the engine's glibc has the data path /data/data/in.opensec.pibolt compiled in.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Android only executes files from nativeLibraryDir, which needs the libraries extracted at install.
            useLegacyPackaging = true
            // Never strip: the engine reads its embedded code from its own file, and the pack is stripped already.
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        // New releases of dependencies are checked by hand (exact pins), not by lint. The target is API 36 by choice.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    // Installs the baseline and startup profiles (src/release/generated/baselineProfiles) on sideloaded installs too.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
