# Rust Native Source (Obfuscated)

## Source code location
The obfuscated Rust source code is hosted in a private repository:
- **Repository**: `git@github.com:soranerai/vpnhide_next_private.git`
- **Path**: `lsposed/native/src/lib.rs` and `main.rs`

## What's here
- `generated/` — Auto-generated files from `data/interfaces.toml` (checked into version control)
- `.gitignore` — Rust build artifacts

## Why the source is private
1. **Proprietary detection logic** — ~40 verification checks that detect VPN hiding methods
2. **Obfuscation techniques** — 5 levels applied: LTO, XOR-string encryption, bytecode VM, direct syscalls, anti-debug
3. **IP protection** — Prevents reverse-engineering of evasion methods

## Building
The CI pipeline (`.github/workflows/ci.yml`) automatically:
1. Clones the private repo (with fine-grained PAT)
2. Builds the Rust crate (`cargo build --release`)
3. Publishes `libvpnhide_checks.so` as a GitHub Release asset
4. Downloads it into `src/main/jniLibs/arm64-v8a/` for the Android build

**Manual build** (requires access to private repo):
```bash
git clone git@github.com:soranerai/vpnhide_next_private.git
cd vpnhide_next_private/lsposed/native
cargo ndk -t arm64-v8a build --release
```

## Anti-debug protection
All 26 verification functions (`check_*`) include runtime detection for:
- **Frida instrumentation** — scans `/proc/self/maps`
- **GDB debugger** — checks `/proc/self/status` TracerPid
- **Reaction** — returns plausible false results, prevents behavior-based detection
