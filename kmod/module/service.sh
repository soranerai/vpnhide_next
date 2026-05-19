#!/system/bin/sh
# Resolves package names → UIDs for kmod and lsposed at boot.
# kmod targets → /proc/vpnhide_targets
# lsposed targets → /data/system/vpnhide_uids.txt

KMOD_TARGETS="/data/adb/vpnhide_kmod/targets.txt"
LSPOSED_TARGETS="/data/adb/vpnhide_lsposed/targets.txt"
PORT_TARGETS="/data/adb/vpnhide_ports/observers.txt"
SS_UIDS_FILE="/data/system/vpnhide_uids.txt"

# Get the directory where the script is located
MODDIR="$(cd "$(dirname "$0")" && pwd)"
CTL="$MODDIR/vpnhide-ctl"
DEV_NODE="/dev/vpnhide_ctrl"

log -t vpnhide "service.sh starting: MODDIR=$MODDIR"

# Since we use IOCTL now, we don't need to wait for proc files, but we should
# verify the module is actually there.
for i in $(seq 1 10); do
    [ -c "$DEV_NODE" ] && break
    lsmod | grep -q vpnhide_kmod && [ -c "$DEV_NODE" ] && break
    sleep 1
done
chmod +x "$CTL"

# Wait until PackageManager has actually indexed user-installed apps.
# `pm list packages` starts responding very early in boot but returns
# only system packages for several more seconds.
for i in $(seq 1 60); do
    if pm list packages -U 2>/dev/null | grep -q "^package:dev.soranerai.vpnhidenext "; then
        break
    fi
    sleep 1
done

# Give PM a moment to settle after the app becomes visible
sleep 2

# Since we use IOCTL now, we don't need to wait for proc files, but we should
# verify the module is actually there.
for i in $(seq 1 10); do
    [ -c "$DEV_NODE" ] && break
    lsmod | grep -q vpnhide_kmod && [ -c "$DEV_NODE" ] && break
    sleep 1
done

if [ ! -c "$DEV_NODE" ]; then
    log -t vpnhide "kernel module control node not found, skipping kmod UID resolution"
fi

# Detect SQLite database
DB="/data/system/vpnhide/vpnhide_config.db"
SQLITE="$MODDIR/sqlite3"
[ -f "$SQLITE" ] || SQLITE="/system/bin/sqlite3"
[ -f "$SQLITE" ] || SQLITE="/data/adb/magisk/sqlite3"
[ -f "$SQLITE" ] || SQLITE="$(which sqlite3 2>/dev/null)"

if [ -f "$DB" ] && [ -x "$SQLITE" ]; then
    log -t vpnhide "boot: database detected, applying rules from DB"
    
    # 1. VPN targets
    KMOD_UIDS="$($SQLITE "$DB" "SELECT uid FROM app_protection WHERE kmod = 1 AND uid != 0" | xargs)"
    
    # Resolve the app itself (dev.soranerai.vpnhidenext) UID and add it to targets (VPN hiding only)
    SELF_UID="$(pm list packages -U --user all 2>/dev/null | grep "^package:dev.soranerai.vpnhidenext " | awk '{print $2}' | sed 's/uid://' | tr ',' '\n' | head -n 1)"
    if [ -n "$SELF_UID" ]; then
        KMOD_UIDS="$KMOD_UIDS $SELF_UID"
    fi

    if [ -n "$KMOD_UIDS" ]; then
        KMOD_UIDS="$(echo "$KMOD_UIDS" | tr ' ' '\n' | grep -v '^$' | sort -u | xargs)"
        log -t vpnhide "boot: applying VPN targets: $KMOD_UIDS"
        # shellcheck disable=SC2086
        "$CTL" targets $KMOD_UIDS
    fi

    # 2. Interface prefixes
    PREFIXES="$($SQLITE "$DB" "SELECT prefix FROM iface_prefixes" | xargs)"
    if [ -n "$PREFIXES" ]; then
        log -t vpnhide "boot: applying interface prefixes: $PREFIXES"
        # shellcheck disable=SC2086
        "$CTL" iface_prefixes $PREFIXES
    fi

    # 3. Port rules
    # This part is complex: we need to group rules by UID for the `port_rules` command.
    # We combine per-app rules and mass rules.
    PORT_UIDS="$($SQLITE "$DB" "SELECT uid FROM app_protection WHERE portHiding = 1 AND uid != 0")"
    if [ -n "$PORT_UIDS" ]; then
        log -t vpnhide "boot: applying port rules from DB"
        PORT_ARGS=""
        MASS_RULES="$($SQLITE "$DB" "SELECT startPort, endPort, protocol FROM mass_port_rules WHERE enabled = 1" | tr '|' ' ')"
        
        for U in $PORT_UIDS; do
            # Get per-app rules for this UID
            # We join with app_protection to filter by uid
            APP_RULES="$($SQLITE "$DB" "SELECT pr.startPort, pr.endPort, pr.protocol FROM port_rules pr JOIN app_protection a ON pr.packageName = a.packageName AND pr.userId = a.userId WHERE a.uid = $U AND pr.enabled = 1" | tr '|' ' ')"
            
            ALL_RULES="$APP_RULES $MASS_RULES"
            # count rules (each rule is 3 numbers)
            # shellcheck disable=SC2086
            RULE_COUNT=$(echo $ALL_RULES | wc -w)
            RULE_COUNT=$((RULE_COUNT / 3))
            
            if [ "$RULE_COUNT" -gt 0 ]; then
                PORT_ARGS="$PORT_ARGS $U $RULE_COUNT $ALL_RULES"
            else
                # Default: block all ports if no specific rules
                PORT_ARGS="$PORT_ARGS $U 1 0 65535 2"
            fi
        done
        
        if [ -n "$PORT_ARGS" ]; then
            # shellcheck disable=SC2086
            "$CTL" port_rules $PORT_ARGS
        fi
    fi
else
    log -t vpnhide "boot: database or sqlite3 not found, falling back to legacy text files"
    
    # [LEGACY FALLBACK START]
    # resolve_uids <targets_file> — prints a space-separated list of UIDs to stdout.
    resolve_uids() {
        local targets_file="$1"
        [ -f "$targets_file" ] || return
        
        local all_pkgs=""
        local uids=""
        
        while IFS= read -r line || [ -n "$line" ]; do
            local entry
            entry="$(echo "$line" | tr -d '[:space:]')"
            [ -z "$entry" ] && continue
            case "$entry" in \#*) continue ;; esac

            # If it's a numeric UID, just add it directly
            case "$entry" in
                "" | *[!0-9]*) ;;
                *)
                    uids="$uids $entry"
                    continue
                    ;;
            esac

            # Lazy-load all packages if needed
            [ -z "$all_pkgs" ] && all_pkgs="$(pm list packages -U --user all 2>/dev/null)"
            [ -n "$all_pkgs" ] || break

            local pkg
            local user_id
            if echo "$entry" | grep -q ":"; then
                pkg="${entry%:*}"
                user_id="${entry#*:}"
            else
                pkg="$entry"
                user_id="all"
            fi
            
            local pkg_esc
            pkg_esc="$(echo "$pkg" | sed 's/\./\\./g')"
            
            # Search in the cached list
            local uid_csv
            uid_csv="$(echo "$all_pkgs" | awk -v p="^package:${pkg_esc}[ :]" '$0 ~ p { sub(/.*uid:/, "", $0); print $0 }')"
            
            if [ -n "$uid_csv" ]; then
                local expanded
                expanded="$(echo "$uid_csv" | tr ',\n' '  ')"
                uids="$uids $expanded"
            fi
        done < "$targets_file"
        
        [ -n "$uids" ] && echo "$uids" | tr ' ' '\n' | grep -v '^$' | sort -u | xargs
    }

    # Resolve kmod targets
    if [ -f "$KMOD_TARGETS" ]; then
        KMOD_UIDS="$(resolve_uids "$KMOD_TARGETS")"
        if [ -n "$KMOD_UIDS" ]; then
            # shellcheck disable=SC2086
            "$CTL" targets $KMOD_UIDS
        fi
    fi

    # Resolve interface prefixes
    IFACE_PREFIXES_FILE="/data/adb/vpnhide_kmod_interfaces.txt"
    if [ -f "$IFACE_PREFIXES_FILE" ]; then
        PREFIXES="$(grep -v "^#" "$IFACE_PREFIXES_FILE" | grep -v "^$" | xargs)"
        if [ -n "$PREFIXES" ]; then
            # shellcheck disable=SC2086
            "$CTL" iface_prefixes $PREFIXES
        fi
    fi
    # [LEGACY FALLBACK END]
fi

# Cleanup old legacy files
for f in "$SS_UIDS_FILE" \
         /data/system/vpnhide_hidden_pkgs.txt \
         /data/system/vpnhide_observer_uids.txt; do
    [ -f "$f" ] && rm -f "$f"
done

# Re-seed debug logging
SS_DEBUG_LOGGING="/data/system/vpnhide_debug_logging"
if [ -f "$SS_DEBUG_LOGGING" ]; then
    # shellcheck disable=SC2046
    $CTL debug $(cat "$SS_DEBUG_LOGGING")
fi

log -t vpnhide "service.sh finished"