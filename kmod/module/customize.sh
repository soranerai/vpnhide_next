#!/system/bin/sh
SKIPUNZIP=0
MOD_VER="$(grep '^version=' "$MODPATH/module.prop" | cut -d= -f2)"
ui_print "- VPNHide Next (kernel) ${MOD_VER:-unknown}"
ui_print "- Installing kernel module to $MODPATH"

# Persistent config directories (survives module updates)
PERSIST_DIR="/data/adb/vpnhide_kmod"
PERSIST_TARGETS="$PERSIST_DIR/targets.txt"

PERSIST_PORTS_DIR="/data/adb/vpnhide_ports"
PERSIST_OBSERVERS="$PERSIST_PORTS_DIR/observers.txt"

mkdir -p "$PERSIST_DIR"
set_perm "$PERSIST_DIR" 0 0 0755

# Seed empty targets on fresh install
if [ ! -f "$PERSIST_TARGETS" ]; then
    cat > "$PERSIST_TARGETS" <<'EOF'
# vpnhide-kmod target apps
# One package name per line. Lines starting with '#' are comments.
# Managed via the VPNHide Next app.
EOF
fi
set_perm "$PERSIST_TARGETS" 0 0 0644

mkdir -p "$PERSIST_PORTS_DIR"
set_perm "$PERSIST_PORTS_DIR" 0 0 0755

# Seed empty observers list on fresh install
if [ ! -f "$PERSIST_OBSERVERS" ]; then
    cat > "$PERSIST_OBSERVERS" <<'EOF'
# vpnhide-ports observers
# One package name per line. Lines starting with '#' are comments.
# Managed via the VPNHide Next app.
EOF
fi
set_perm "$PERSIST_OBSERVERS" 0 0 0644

set_perm "$MODPATH/vpnhide_kmod.ko" 0 0 0644
set_perm "$MODPATH/vpnhide-ctl" 0 0 0755
set_perm "$MODPATH/vpnhide_ports_apply.sh" 0 0 0755

ui_print "- Targets: $PERSIST_TARGETS"
ui_print "- Observers: $PERSIST_OBSERVERS"
ui_print "- Pick apps via the VPNHide Next app."
