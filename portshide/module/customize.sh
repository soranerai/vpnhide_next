#!/system/bin/sh
SKIPUNZIP=0
MOD_VER="$(grep '^version=' "$MODPATH/module.prop" | cut -d= -f2)"
ui_print "- VPNHide Next (Ports) ${MOD_VER:-unknown}"
ui_print "- Installing to $MODPATH"

# Persistent config directory (survives module updates)
PERSIST_DIR="/data/adb/vpnhide_ports"
PERSIST_OBSERVERS="$PERSIST_DIR/observers.txt"

mkdir -p "$PERSIST_DIR"
set_perm "$PERSIST_DIR" 0 0 0755

# Seed empty observers list on fresh install
if [ ! -f "$PERSIST_OBSERVERS" ]; then
    cat > "$PERSIST_OBSERVERS" <<'EOF'
# vpnhide-ports observers
# One package name per line. Lines starting with '#' are comments.
# Managed via the VPNHide Next app.
EOF
fi
set_perm "$PERSIST_OBSERVERS" 0 0 0644

set_perm "$MODPATH/vpnhide_ports_apply.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "- Observers: $PERSIST_OBSERVERS (preserved across updates)"
ui_print "- Pick apps via the VPNHide Next app → Protection → Ports."
