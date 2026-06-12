#!/system/bin/sh
# Clean up all persistent state files and directories created by VPNHide Next upon module uninstallation

# 1. Clean up /data/system directories and database
rm -rf /data/system/vpnhide
rm -f /data/system/vpnhide_uids.txt
rm -f /data/system/vpnhide_hidden_pkgs.txt
rm -f /data/system/vpnhide_observer_uids.txt
rm -f /data/system/vpnhide_debug_logging
rm -f /data/system/vpnhide_hook_active
rm -f /data/system/vpnhide_hook_stats.txt
rm -f /data/system/vpnhide/vpnhide_hook_stats_req
rm -f /data/system/vpnhide_physical_ip

# 2. Clean up /data/adb directories and legacy target files
rm -rf /data/adb/vpnhide
rm -rf /data/adb/vpnhide_kmod
rm -rf /data/adb/vpnhide_lsposed
rm -rf /data/adb/vpnhide_ports
rm -f /data/adb/vpnhide_kmod_interfaces.txt
