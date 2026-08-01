#!/system/bin/sh
SKIPUNZIP=0
MOD_VER="$(grep '^version=' "$MODPATH/module.prop" | cut -d= -f2)"
ui_print "- VPNHide Next (kernel) ${MOD_VER:-unknown}"
ui_print "- Installing kernel module to $MODPATH"

set_perm "$MODPATH/vpnhide_kmod.ko" 0 0 0644
set_perm "$MODPATH/vpnhide-ctl" 0 0 0755
set_perm "$MODPATH/vpnhide-daemon" 0 0 0755
set_perm "$MODPATH/sepolicy.rule" 0 0 0644

# Policy state is owned by the application DE files directory. Do not create
# or migrate target lists in /data/adb or /data/system here.
ui_print "- Policy is managed from the VPNHide Next app"
