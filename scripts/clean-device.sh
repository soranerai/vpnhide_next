#!/usr/bin/env bash
# Wipe all VPNHide Next data from a connected device for clean install testing.
# Usage: ./scripts/clean-device.sh
set -euo pipefail

echo "Uninstalling app..."
adb shell pm uninstall dev.soranerai.vpnhidenext 2>/dev/null || true

echo "Removing persistent data..."
adb shell su -c "rm -rf /data/adb/vpnhide_kmod /data/adb/vpnhide_lsposed" 2>/dev/null || true

echo "Removing runtime files..."
adb shell su -c "rm -f /data/system/vpnhide_uids.txt" 2>/dev/null || true
adb shell su -c "rm -rf /data/user/0/dev.soranerai.vpnhidenext" 2>/dev/null || true

echo "Done. Reboot recommended if modules were installed."
