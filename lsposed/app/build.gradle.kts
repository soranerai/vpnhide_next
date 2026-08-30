import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.atomicfu)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.gobley.cargo)
}

// Build and package libvpnhide_checks.so from this repository's Rust crate.
// Gobley wires the aarch64 Android cdylib into the APK automatically.
cargo {
    packageDirectory = layout.projectDirectory.dir("../native")
    // JVM tests do not load the Rust library. Disabling their cargo build also
    // avoids compiling Android-specific ioctl shapes against the host libc.
    builds.withType(gobley.gradle.cargo.dsl.CargoJvmBuild::class.java).configureEach {
        androidUnitTest.set(false)
    }
}

// Gobley is the sole producer of libvpnhide_checks.so. A leftover manual
// jniLibs copy takes precedence during Android packaging and can silently pair
// stale Rust exports with newer generated Kotlin bindings.
val verifyNoLegacyJniLibs by tasks.registering {
    doLast {
        val legacyDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
        if (legacyDir.exists()) {
            throw GradleException(
                "Remove ${legacyDir.relativeTo(projectDir)}: libvpnhide_checks.so is built by Gobley.",
            )
        }
    }
}

tasks.matching { it.name.startsWith("assemble") }.configureEach {
    dependsOn(verifyNoLegacyJniLibs)
}

android {
    namespace = "dev.soranerai.vpnhidenext"
    compileSdk = 35

    // Effective build version from ../scripts/build-version.py:
    //   release tag    -> "0.6.2"
    //   dev build      -> "0.6.1-5-gabc1234" (+"-dirty" if uncommitted)
    //   no git         -> VERSION file
    // Python instead of bash so Windows contributors can build without WSL.
    // Script is stdlib-only — no `uv` / pip install needed. `python` on
    // Windows, `python3` elsewhere: Ubuntu 22.04+ ships only the latter,
    // Windows python.org / Store installer ships only the former.
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val pythonExe = if (isWindows) "python" else "python3"
    val buildVersion: String =
        providers
            .exec {
                commandLine(
                    pythonExe,
                    rootProject.projectDir.parentFile.resolve("scripts/build-version.py").absolutePath,
                )
            }.standardOutput.asText
            .get()
            .trim()

    defaultConfig {
        applicationId = "dev.soranerai.vpnhidenext"
        minSdk = 29
        targetSdk = 35
        versionCode = 20504
        versionName = buildVersion

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["password"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["password"] as String
            } else {
                // Если ключа нет, подставляем дебажный
                val debugConfig = getByName("debug")
                keyAlias = debugConfig.keyAlias
                keyPassword = debugConfig.keyPassword
                storeFile = debugConfig.storeFile
                storePassword = debugConfig.storePassword
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }

    // Skip Android Lint on test source sets. Our `src/test/` is pure JVM
    // unit-test logic (filter/recommendation builders) — no Android
    // lifecycle, no layouts, no context misuse. Functional bugs are
    // caught by `:app:testDebugUnitTest`. Saves ~15–20 s of
    // `lintAnalyze*Test` per Lint run.
    //
    // We deliberately leave `checkReleaseBuilds` at its default (true):
    // CI invokes `:app:lintDebug` on PRs (so the release variant isn't
    // analysed there), but ad-hoc `./gradlew :app:lint` on a release
    // build still catches R8/ProGuard-specific issues like MissingRules.
    lint {
        checkTestSources = false
    }
}

dependencies {
    // Modern Xposed API — compileOnly so it is provided by LSPosed/Vector.
    compileOnly("io.github.libxposed:api:102.0.0")

    // Runtime deps for the committed UniFFI FFI bindings
    // (checks/vpnhide_checks.*.kt). Their ABI is built from lsposed/native.
    // Versions match the generated bindings (JNA 5.18.1, atomicfu 0.26.1).
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("org.jetbrains.kotlinx:atomicfu:0.26.1")

    // Android 12 SplashScreen API, backported to API 23+.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Periodic background update-check job (see UpdateCheckWorker).
    implementation(libs.work.runtime.ktx)

    // Compose UI
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.github.oikvpqya.compose.fastscroller:fastscroller-material3:0.3.2")
    implementation("io.github.oikvpqya.compose.fastscroller:fastscroller-indicator:0.3.2")
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.coroutines.test)
}
