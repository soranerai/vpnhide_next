# Kernel Module Source (Private)

## Source code location
The kernel module source code is hosted in a private repository:
- **Repository**: `git@github.com:soranerai/vpnhide_next_private.git`
- **Files**: 
  - `kmod/vpnhide_kmod.c` — Main kernel module (5700+ lines)
  - `kmod/vpnhide_ctl.c` — Control utility
  - `kmod/vpnhide_daemon.c` — Background daemon
  - `kmod/include/vpnhide.h` — Shared ABI header

## What's here (public)
- `Makefile` — Build configuration
- `build.py` — DDK container automation
- `BUILDING.md` — Build documentation
- `include/vpnhide.h` — **Public ABI header** (left here for binary compatibility)
- `parson.c/h` — MIT-licensed JSON library (open source, no changes)
- `module/` — Magisk/KernelSU module payload (shell scripts, metadata)

## Why the source is private
1. **Sensitive hooking logic** — kretprobe-based interface hiding
2. **ioctl protocol** — Custom ioctls for app-kernel communication
3. **IP protection** — Prevents reverse-engineering of obfuscation techniques

## Building
The CI pipeline (`.github/workflows/ci.yml`) automatically:
1. Clones the private repo (with fine-grained PAT)
2. Builds for all 8 GKI variants in DDK containers
3. Publishes `vpnhide-kmod-{kmi}.zip` as GitHub Release assets
4. Publishes to Magisk/KernelSU update JSON

**Manual build** (requires access to private repo):
```bash
git clone git@github.com:soranerai/vpnhide_next_private.git
cd vpnhide_next_private/kmod
./build.py --kmi android14-6.1
```

## Module payload
The `module/` directory contains the Magisk/KernelSU module:
- Loads the `.ko` file via insmod
- Applies configuration from JSON
- Starts the daemon for runtime management
- Provides update-json URL for OTA updates

**Note**: All source code changes compile into the `.ko` file, which is binary-only in this repo.
