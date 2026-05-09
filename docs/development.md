# Development setup

How to build vpnhide from source.

## Prerequisites

- **JDK 17 or later** — what the CI image installs (`openjdk-17-jdk-headless`); local builds with JDK 21 also work. The `lsposed/app` Gradle build sets `sourceCompatibility = 17` and `jvmTarget = "17"`.
- **Android SDK** — install `platforms;android-35`, `build-tools;35.0.0`, `platform-tools` (via Android Studio or `cmdline-tools`). Export `ANDROID_HOME`.
- **Android NDK r28 or later** — export `ANDROID_NDK_HOME` (or drop it in `$ANDROID_HOME/ndk/<version>/`, the scripts auto-detect). The Gobley Gradle plugin used by `lsposed/app` reads `ANDROID_NDK_ROOT`, not `ANDROID_NDK_HOME`, so export both (or alias one to the other) when invoking Gradle directly. r27c builds compile, but the resulting cdylibs trigger an Android 16 KiB-page-size compatibility warning at app start on Pixel 8 Pro / future hardware (`сегмент LOAD не выровнен`); r28+ aligns LOAD segments on 16 KiB by default. (`zygisk/build.rs` and `lsposed/native/build.rs` also pass `-Wl,-z,max-page-size=16384` explicitly so older NDKs stay compatible — defence in depth.)
- **Rust** (latest stable) with the Android target:
  ```sh
  rustup target add aarch64-linux-android
  cargo install cargo-ndk
  ```
- **`podman` or `docker`** — only for building the kernel module via DDK images. See [kmod/BUILDING.md](../kmod/BUILDING.md).
- **`zip`** — packaging module zips.
- **`adb`** — installing builds on a device.

[Gobley](https://github.com/gobley/gobley) (Gradle plugins `dev.gobley.cargo` + `dev.gobley.uniffi`) is what builds the Rust crate at `lsposed/native/` via cargo-ndk and bundles the resulting `libvpnhide_checks.so` plus its UniFFI-generated Kotlin bindings (package `dev.soranerai.vpnhidenext.checks`) into the APK. The plugins are auto-resolved by Gradle from Maven Central — no manual install. Version is pinned in `lsposed/gradle/libs.versions.toml`.

## Repository layout

| Path | Component |
|---|---|
| `zygisk/` | Zygisk native module (Rust, inline `libc` hooks) |
| `lsposed/` | LSPosed module + target-picker Android app (Kotlin, Compose) |
| `kmod/` | Kernel module (C, kretprobes) |
| `portshide/` | Localhost port blocker (shell + iptables) |
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

### zygisk module

```sh
cd zygisk && ./build.py
# → zygisk/target/vpnhide-zygisk.zip
```

The script auto-detects the NDK from `$ANDROID_NDK_HOME` or `~/Android/Sdk/ndk/*`.

### lsposed APK

```sh
cd lsposed && ./gradlew :app:assembleRelease
# → lsposed/app/build/outputs/apk/release/app-release.apk
```

### kernel module

```sh
./kmod/build.py --kmi android14-6.1   # one variant
./kmod/build.py --all                  # every supported GKI
# → vpnhide-kmod-<kmi>.zip at the repo root
```

The script auto-spawns the `ghcr.io/ylarod/ddk-min:<kmi>-<TAG>` container via podman/docker (same image CI uses). For local kernel-source builds via `direnv` and the GKI matrix details, see [kmod/BUILDING.md](../kmod/BUILDING.md).

## Install on device

```sh
# APK
adb install -r lsposed/app/build/outputs/apk/release/app-release.apk

# zygisk / kmod: push to device, install via the Magisk or KernelSU manager app
adb push zygisk/target/vpnhide-zygisk.zip /sdcard/Download/
adb push vpnhide-kmod.zip /sdcard/Download/
```

After flashing kmod or zygisk, reboot the device.

## CI lints (run before pushing)

CI runs the same checks. See [.github/workflows/ci.yml](../.github/workflows/ci.yml) for the authoritative list.

```sh
# Codegen drift — run after editing data/interfaces.toml; CI fails on diff
python3 scripts/codegen-interfaces.py
git diff --quiet  # must be clean

# Python (ruff, config in pyproject.toml). uvx runs without installing anything global.
uvx ruff format --check
uvx ruff check

# Rust
cd zygisk && cargo fmt --check && cargo ndk -t arm64-v8a clippy -- -D warnings
cd ../lsposed/native && cargo fmt --check && cargo ndk -t arm64-v8a clippy -- -D warnings
cd ../zygisk && cargo test
cd ../lsposed/native && cargo test

# C (kernel module)
clang-format --dry-run --Werror kmod/vpnhide_kmod.c
# Host-side test of the generated VPN-iface matcher used by the kernel module
gcc -O2 -Wall -Werror -o /tmp/test_iface_lists kmod/test_iface_lists.c && /tmp/test_iface_lists

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
- [kmod/BUILDING.md](../kmod/BUILDING.md) — kernel-module build deep dive
