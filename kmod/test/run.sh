#!/usr/bin/env bash
# Boot a GKI kernel for <kmi> in QEMU (TCG) and run the vpnhide kmod vector
# tests inside it. Consumes a prebuilt kernel Image + module .ko from the
# per-KMI cache (produced by build-kernel.sh); assembles a throwaway Alpine
# initramfs around init.sh, boots, and parses the serial console for the
# RESULT/SUMMARY lines.
#
# Usage:  kmod/test/run.sh [kmi]      (default: android12-5.10)
# Exit:   0 = all vectors PASS, no panic; non-zero otherwise.
set -euo pipefail

KMI="${1:-android12-5.10}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CACHE="$HERE/.cache"
KDIR="$CACHE/$KMI"

# Inputs default to the local cache (populated by build-kernel.sh), but each
# can be overridden by env so CI can point at artifacts baked into the
# ddk-qemu image / downloaded from the kmod build job:
#   VPNHIDE_QEMU_IMAGE  - kernel Image (baked in the image)
#   VPNHIDE_QEMU_KO     - module .ko   (from the kmod build artifact)
#   VPNHIDE_QEMU_ROOTFS - Alpine minirootfs tarball (baked in the image)
IMAGE="${VPNHIDE_QEMU_IMAGE:-$KDIR/Image}"
KO="${VPNHIDE_QEMU_KO:-$KDIR/vpnhide_kmod.ko}"

# Rule check: Запускай любые билды ТОЛЬКО асинхронно для себя, в фоне.
# (This script boots QEMU inside docker/podman or host; VM boot could take some time)

ALPINE_VER="3.21.2"
ALPINE_TAR="${VPNHIDE_QEMU_ROOTFS:-$CACHE/alpine-minirootfs-$ALPINE_VER-aarch64.tar.gz}"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-$ALPINE_VER-aarch64.tar.gz"

command -v qemu-system-aarch64 >/dev/null || { echo "ERROR: qemu-system-aarch64 not installed"; exit 2; }
[ -f "$IMAGE" ] || { echo "ERROR: kernel missing: $IMAGE"; echo "  run: $HERE/build-kernel.sh $KMI"; exit 2; }
[ -f "$KO" ]    || { echo "ERROR: module missing: $KO";   echo "  run: $HERE/build-kernel.sh $KMI"; exit 2; }

mkdir -p "$CACHE"
[ -f "$ALPINE_TAR" ] || { echo "[run] fetching Alpine minirootfs…"; curl -fsSL "$ALPINE_URL" -o "$ALPINE_TAR"; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
RFS="$WORK/rootfs"
mkdir -p "$RFS"
tar xzf "$ALPINE_TAR" -C "$RFS"
# Copy the prebuilt static aarch64 vpnhide-ctl-host and vpnhide-daemon-host binaries
CTL_BIN="$HERE/../vpnhide-ctl-host"
if [ ! -f "$CTL_BIN" ]; then
	CTL_BIN="$HERE/vpnhide-ctl-host"
fi
[ -f "$CTL_BIN" ] || { echo "ERROR: vpnhide-ctl-host missing: $CTL_BIN"; exit 2; }

DAEMON_BIN="$HERE/../vpnhide-daemon-host"
if [ ! -f "$DAEMON_BIN" ]; then
	DAEMON_BIN="$HERE/vpnhide-daemon-host"
fi
[ -f "$DAEMON_BIN" ] || { echo "ERROR: vpnhide-daemon-host missing: $DAEMON_BIN"; exit 2; }

cp "$KO" "$RFS/vpnhide_kmod.ko"
cp "$CTL_BIN" "$RFS/vpnhide-ctl"
cp "$DAEMON_BIN" "$RFS/vpnhide-daemon"
cp "$HERE/vector_tests.py" "$RFS/vector_tests.py"
cp "$HERE/init.sh" "$RFS/init"
chmod +x "$RFS/init" "$RFS/vpnhide-ctl" "$RFS/vpnhide-daemon" "$RFS/vector_tests.py"
( cd "$RFS" && find . | cpio -o -H newc 2>/dev/null | gzip > "$WORK/initramfs.cpio.gz" )

LOG="$WORK/serial.log"
echo "[run] $KMI: booting $(basename "$IMAGE") in QEMU (TCG, no KVM)…"
# romfile= disables the virtio-net PCI option ROM (iPXE) so we don't need the
# ipxe-qemu package — only matters for PXE boot, which we never do; networking
# (for apk in the VM) still works.
timeout 300 qemu-system-aarch64 \
	-machine virt -cpu max -accel tcg,thread=multi,tb-size=1024 \
	-smp 4 -m 2G \
	-kernel "$IMAGE" -initrd "$WORK/initramfs.cpio.gz" \
	-append "console=ttyAMA0 panic=-1 rdinit=/init" \
	-netdev user,id=n0 -device virtio-net-pci,netdev=n0,romfile= \
	-display none -no-reboot -serial "file:$LOG" >/dev/null 2>&1 || true

echo "------------------------- test output -------------------------"
if ! sed -n '/VPNHIDE-QEMU-TEST START/,/VPNHIDE-QEMU-TEST END/p' "$LOG" |
	grep -E 'KREL|IPROUTE2|INSMOD|REGISTERED|RESULT|PANIC|SUMMARY'; then
	echo "ERROR: no test output — kernel did not boot or init failed"
	echo "--- last 30 serial lines ---"; tail -30 "$LOG"
	exit 1
fi
echo "---------------------------------------------------------------"

summary="$(grep -oE 'SUMMARY pass=[0-9]+ fail=[0-9]+ panic=[0-9]+ registered=[0-9]+' "$LOG" | tail -1 || true)"
[ -n "$summary" ] || { echo "ERROR: VM did not reach SUMMARY (boot/insmod failure)"; exit 1; }
fail="$(sed -E 's/.*fail=([0-9]+).*/\1/' <<<"$summary")"
panic="$(sed -E 's/.*panic=([0-9]+).*/\1/' <<<"$summary")"

if [ "$fail" -eq 0 ] && [ "$panic" -eq 0 ]; then
	echo "[run] $KMI: PASS ($summary)"
	exit 0
fi
echo "[run] $KMI: FAIL ($summary)"
echo "=== FULL SERIAL LOG ==="
cat "$LOG"
exit 1
