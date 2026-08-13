# Development setup

How to build vpnhide_next from source.

## Prerequisites

- **JDK 17 or later** — what the CI image installs (`openjdk-17-jdk-headless`); local builds with JDK 21 also work. The `lsposed/app` Gradle build sets `sourceCompatibility = 17` and `jvmTarget = "17"`.
- **Android SDK** — install `platforms;android-35`, `build-tools;35.0.0`, `platform-tools` (via Android Studio or `cmdline-tools`). Export `ANDROID_HOME`.
- **`adb`** — installing builds on a device.

The diagnostic Rust library and kernel backend are built in the private
`vpnhide_next_private` repository. The public APK build consumes the
prebuilt `libvpnhide_checks.so` from CI (or from `scripts/build-app.sh` when
the private checkout is available); the committed Kotlin FFI bindings remain
in this repository.

## Repository layout

| Path | Component |
|---|---|
| `lsposed/` | LSPosed module + target-picker Android app (Kotlin, Compose) |
| `scripts/` | Release & changelog tooling |
| `update-json/` | Magisk/KSU update metadata |
| `docs/` | Contributor documentation (this directory) |

Each module has its own README with architecture and design notes.

## Signing keystore (required for lsposed)

`lsposed/app/build.gradle.kts` routes both the `debug` and `release` build types through a single signing config that reads `lsposed/keystore.properties`. Without that file, `./gradlew assembleDebug` and `:app:assembleRelease` fail with:

> SigningConfig 'release' is missing required property 'storeFile'

Create `lsposed/keystore.properties` (git-ignored):

```properties
storeFile=/absolute/path/to/your.jks
keyAlias=yourAlias
password=yourPassword
```

Generate a keystore if you don't have one:

```sh
keytool -genkey -v -keystore ~/vpnhide.jks \
    -keyalg RSA -keysize 4096 -validity 36500 -alias vpnhide
```

## Build each module

### lsposed APK

```sh
cd lsposed && ./gradlew :app:assembleRelease
# → lsposed/app/build/outputs/apk/release/app-release.apk
```

Kernel modules and KPatch builds are produced in the private backend
repository. This public repository only consumes their release artifacts.

## Install on device

```sh
# APK
adb install -r lsposed/app/build/outputs/apk/release/app-release.apk

# Install the backend-produced kmod/KPatch package via the manager app.
```

After installing a kernel component, reboot the device.

## CI lints (run before pushing)

CI runs the same checks. See [.github/workflows/ci.yml](../.github/workflows/ci.yml) for the authoritative list.

```sh
# Compatibility matrix codegen — run after editing data/compatibility.json
python3 scripts/codegen-compatibility.py
git diff --quiet -- lsposed/app/src/main/kotlin/dev/soranerai/vpnhidenext/generated/CompatibilityMatrix.kt

# Codegen drift — run after editing data/interfaces.toml; CI fails on diff
python3 scripts/codegen-interfaces.py
git diff --quiet  # must be clean

# Python (ruff, config in pyproject.toml). uvx runs without installing anything global.
uvx ruff format --check
uvx ruff check

# Kotlin
ktlint "lsposed/**/*.kt"
cd lsposed && ./gradlew :app:lintDebug :app:testDebugUnitTest
```

## Build versions

Every module zip and the APK carry a version string derived from git at build time:

- on a release tag `vX.Y.Z` → `X.Y.Z`
- otherwise → `X.Y.Z-N-gSHA` (commits since the nearest tag + short hash, plus `-dirty` if the working tree has uncommitted changes)

So a locally-built dev APK shows up in Android Settings as e.g. `0.6.1-5-gabc1234-dirty`, and the same string lands in `module.prop` inside the zip. The committed `module.prop` files themselves stay at the last release number — the version is stamped into a staging copy per build.

See [releasing.md](releasing.md#build-versions) for details.

## More docs

- [releasing.md](releasing.md) — version bump, tag, release flow
- [changelog.md](changelog.md) — how changelog entries flow from JSON → markdown
