# Building vpnhide-kmod

Most users should download pre-built modules from [Releases](https://github.com/soranerai/vpnhide_next/releases) — builds are provided for all supported GKI generations. This guide is for contributors or users who need to build from source.

## Quick build

One command — same script CI runs, no container invocation to memorize:

```bash
./kmod/build.py --kmi android14-6.1     # one variant
./kmod/build.py --all                   # every supported GKI
```

The script auto-detects whether to build natively (you're already inside the DDK image, or you've pointed `--kdir` at a kernel source tree) or to spawn a `ghcr.io/ylarod/ddk-min:<kmi>-<TAG>` container via podman/docker. On rootless podman (Fedora etc) it adds `--userns=keep-id` and `:Z` automatically. The output is `vpnhide-kmod-<kmi>.zip` at the repo root.

Requires `podman` or `docker`. The container image weighs ~1 GB per GKI variant on first pull.

### Identifying your GKI generation

```bash
adb shell uname -r
```

The output looks like `6.1.75-android14-11-g...` — the generation is `android14-6.1`.

> **Note:** the `android14` part is NOT your Android version — it's the kernel generation. All Pixels from 6 to 9a share the same `android14-6.1` kernel. Pixel 10 series moves to `android16-6.12`.

| `uname -r` pattern | GKI generation |
|---|---|
| `5.10.xxx-android12-...` | android12-5.10 |
| `5.10.xxx-android13-...` | android13-5.10 |
| `5.15.xxx-android13-...` | android13-5.15 |
| `5.15.xxx-android14-...` | android14-5.15 |
| `6.1.xxx-android14-...` | android14-6.1 |
| `6.6.xxx-android15-...` | android15-6.6 |
| `6.12.xxx-android16-...` | android16-6.12 |
| `6.18.xxx-android17-...` | android17-6.18 |

## Local build with kernel source

If you prefer building against a local kernel source tree (e.g. for development or debugging), point `--kdir` at it. The script then runs natively without spinning up a container:

```bash
./kmod/build.py --kdir ~/kernels/android14-6.1 --kmi android14-6.1
```

You can also drop a `kmod/.env` file with `KDIR=` / `KERNEL_SRC=` / `CLANG_DIR=` (see `.env.example`) and use [`direnv`](https://direnv.net/) to load it automatically. The script picks those up via env, no flag needed.

## Install and test

```bash
adb push vpnhide-kmod-<kmi>.zip /sdcard/Download/
# Install via KernelSU-Next manager -> Modules -> Install from storage
# Reboot
```

Verify after reboot:

```bash
adb shell "su -c 'lsmod | grep vpnhide'"
adb shell "su -c 'dmesg | grep vpnhide'"
adb shell "su -c 'cat /proc/vpnhide_targets'"
```

## Troubleshooting

**`insmod: Exec format error`** — symvers CRC mismatch. Rebuild via the DDK container (`./kmod/build.py --kmi <kmi>`); the container image carries matched symvers.

**`insmod: File exists`** — module already loaded. `rmmod vpnhide_kmod` first.

**kretprobe not firing** — check `dmesg | grep vpnhide` for registration messages and `/proc/vpnhide_targets` for correct UIDs. Target app UIDs change on reinstall — re-resolve via the VPNHide Next app.

**`./kmod/build.py` says "neither podman nor docker found"** — install one (`dnf install podman` / `apt install docker.io`), or build natively against a local kernel source via `--kdir`.

**Bumping the DDK image tag** — single source of truth is `DDK_IMAGE_TAG` in `kmod/build.py`. Both this script and `.github/workflows/ci.yml`'s kmod matrix pin to the same value, so update both together.
