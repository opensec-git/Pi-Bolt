import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Callable

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

// Debug builds only: point the in-app update at a test server (android/README.md, "Updates"). Release builds always use
// pi-bolt.opensec.in/android.json and GitHub release downloads; these fields are empty there.
val updateManifestOverride: String = providers.environmentVariable("PIBOLT_UPDATE_MANIFEST_URL").orNull.orEmpty()
val updateApkPrefixOverride: String = providers.environmentVariable("PIBOLT_UPDATE_APK_PREFIX").orNull.orEmpty()

// GitHub sign-in through the OAuth device flow needs the client id of a GitHub OAuth App with Device Flow enabled
// (android/README.md, "GitHub"). From the Gradle property `pibolt.githubClientId` or PIBOLT_GITHUB_CLIENT_ID; empty by
// default, and then the app offers a personal access token instead. A client id is public (not a secret).
val githubClientId: String = (providers.gradleProperty("pibolt.githubClientId").orNull ?: providers.environmentVariable("PIBOLT_GITHUB_CLIENT_ID").orNull)
    .orEmpty().trim()
    .also { require(it.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' }) { "pibolt.githubClientId has unexpected characters" } }

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The on-device engine pack (android/engine/build-pack.sh, CONTRACT.md): jniLibs/arm64-v8a and assets/engine. Added when
// built; without it the APK has no engine (remote control still works).
val enginePack = rootDir.resolve("engine/out")

// Mermaid for diagrams in the chat (ui/diagram), from the npm registry at an exact version. The tarball must match
// npm's sha512 integrity for that version, so a changed or tampered release fails the build. Only dist/mermaid.min.js
// is packaged (assets/diagram/); its licence and the bundled projects' licences are in res/raw/license_mermaid.txt.
val mermaidVersion = "12.2.0"
val mermaidIntegrity = "sha512-w7nr/SFrLO7BaVTs3dQTvYSV6nYSBj0Yy9DjBhsIJtAcHqRzXSq4Pg32ujHZ/AdWsAMNEvvhbm5dt9E2tOiHzw=="

abstract class FetchMermaid : DefaultTask() {
    @get:Input abstract val version: Property<String>

    @get:Input abstract val integrity: Property<String>

    /** Survives `clean`, so a rebuild does not download again. */
    @get:Internal abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val archives: ArchiveOperations

    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun fetch() {
        val name = "mermaid-${version.get()}.tgz"
        val tgz = cacheDir.file(name).get().asFile
        if (!tgz.isFile || integrityOf(tgz) != integrity.get()) {
            tgz.parentFile.mkdirs()
            val part = File(tgz.parentFile, "$name.part")
            URI("https://registry.npmjs.org/mermaid/-/$name").toURL().openStream().use { input ->
                part.outputStream().use { input.copyTo(it) }
            }
            val got = integrityOf(part)
            if (got != integrity.get()) {
                part.delete()
                throw GradleException("mermaid $name: integrity $got does not match the pinned ${integrity.get()}")
            }
            if (!part.renameTo(tgz)) throw GradleException("mermaid: cannot move $part to $tgz")
        }
        files.sync {
            from(archives.tarTree(archives.gzip(tgz))) {
                include("package/dist/mermaid.min.js")
                eachFile { path = "diagram/${this.name}" }
            }
            includeEmptyDirs = false
            into(outputDir)
        }
    }

    private fun integrityOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return "sha512-" + Base64.getEncoder().encodeToString(digest.digest())
    }
}

val fetchMermaid = tasks.register<FetchMermaid>("fetchMermaid") {
    version.set(mermaidVersion)
    integrity.set(mermaidIntegrity)
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/pibolt-downloads"))
    outputDir.set(layout.buildDirectory.dir("generated/mermaid"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchMermaid, FetchMermaid::outputDir)
    }
}

android {
    namespace = "opensec.pibolt.app"
    compileSdk = 36
    // Builds libtermux.so (src/main/jni). Pinned; CI installs this exact version (.github/workflows/android.yml).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "in.opensec.pibolt"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "UPDATE_MANIFEST_OVERRIDE", "\"\"")
        buildConfigField("String", "UPDATE_APK_PREFIX_OVERRIDE", "\"\"")
        buildConfigField("String", "GITHUB_CLIENT_ID", quoted(githubClientId))
        // The engine and its userland are linux-arm64 only.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Termux's pty helper, vendored (Apache-2.0) and linked for 16 KB pages: the AAR's prebuilt one is 4 KB aligned.
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            // Pinned like the NDK; CI installs it when the runner lacks it.
            version = "3.22.1"
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
        debug {
            buildConfigField("String", "UPDATE_MANIFEST_OVERRIDE", quoted(updateManifestOverride))
            buildConfigField("String", "UPDATE_APK_PREFIX_OVERRIDE", quoted(updateApkPrefixOverride))
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

composeCompiler {
    // :protocol's immutable data classes (and File, JsonObject) as stable: see the file's header.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
    // Compose compiler reports, for smoothness work (android/README.md, "Frame timing"):
    // ./gradlew :app:compileReleaseKotlin -Ppibolt.composeReports=true writes each class's stability and each
    // composable's skippability to app/build/compose-reports, and the totals to app/build/compose-metrics.
    if (providers.gradleProperty("pibolt.composeReports").orNull == "true") {
        reportsDestination = layout.buildDirectory.dir("compose-reports")
        metricsDestination = layout.buildDirectory.dir("compose-metrics")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Termux's terminal-emulator is used for its Java classes only: its AAR has no resources, and its prebuilt libtermux.so
// is 4 KB page aligned, so the classes are taken from the (sha256-verified) AAR as a jar and the native library is
// built from the vendored termux.c (src/main/jni). Nothing of the AAR's jni/ reaches the merge, the APK or lint.
val termuxEmulatorAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
}
val termuxEmulatorClasses = tasks.register<Copy>("termuxEmulatorClasses") {
    from(Callable { zipTree(termuxEmulatorAar.singleFile) }) { include("classes.jar") }
    into(layout.buildDirectory.dir("termux-emulator"))
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
    // WebViewAssetLoader for the offscreen diagram renderer (ui/diagram).
    implementation(libs.androidx.webkit)
    // The terminal: a pty (JNI, libtermux.so) and a VT emulator, and its Android view.
    termuxEmulatorAar(libs.termux.terminal.emulator)
    implementation(files(termuxEmulatorClasses.map { it.destinationDir.resolve("classes.jar") }))
    implementation(libs.termux.terminal.view) {
        exclude(group = "com.github.termux.termux-app", module = "terminal-emulator")
    }
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
    testImplementation(libs.okhttp.mockwebserver)
}
