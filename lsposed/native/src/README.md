# Rust native source

## Source code location
The source for `libvpnhide_checks.so` is public and lives in this directory.

## What's here
- `generated/` — auto-generated files from `data/interfaces.toml` (checked in)
- `lib.rs` — UniFFI API and diagnostic checks
- `main.rs` — command-line test entry point

## Building
The public CI pipeline builds this crate through the Gobley Cargo Gradle plugin
and packages `libvpnhide_checks.so` directly into the APK.

`scripts/build-app.sh` builds the APK and its native library together. To
build only the Android library manually:
```bash
cd lsposed
./gradlew :app:cargoBuildAndroidArm64Release
```

For host tests:

```bash
cd lsposed/native
cargo test
```

For a direct Android Cargo build:

```bash
cd lsposed/native
cargo ndk -t arm64-v8a build --release
```

## Anti-debug protection
All 26 verification functions (`check_*`) include runtime detection for:
- **Frida instrumentation** — scans `/proc/self/maps`
- **GDB debugger** — checks `/proc/self/status` TracerPid
- **Reaction** — returns plausible false results, prevents behavior-based detection
