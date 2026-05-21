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
APPLY_PORTS="$MODDIR/vpnhide_ports_apply.sh"
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

apply_all_rules_from_db() {
    if [ -f "$DB" ] && [ -x "$SQLITE" ]; then
        log -t vpnhide "applying rules from DB..."
        
        # 1. VPN targets
        local kmod_uids
        kmod_uids="$($SQLITE "$DB" "SELECT uid FROM app_protection WHERE kmod = 1 AND uid != 0" | xargs)"
        
        # Resolve the app itself (dev.soranerai.vpnhidenext) UID and add it to targets (VPN hiding only)
        local self_uid
        self_uid="$(pm list packages -U --user all 2>/dev/null | grep "^package:dev.soranerai.vpnhidenext " | awk '{print $2}' | sed 's/uid://' | tr ',' '\n' | head -n 1)"
        if [ -n "$self_uid" ]; then
            kmod_uids="$kmod_uids $self_uid"
        fi

        if [ -n "$kmod_uids" ]; then
            kmod_uids="$(echo "$kmod_uids" | tr ' ' '\n' | grep -v '^$' | sort -u | xargs)"
            log -t vpnhide "applying VPN targets: $kmod_uids"
            # shellcheck disable=SC2086
            "$CTL" targets $kmod_uids
        else
            "$CTL" targets
        fi

        # 2. Interface prefixes
        local prefixes
        prefixes="$($SQLITE "$DB" "SELECT prefix FROM iface_prefixes" | xargs)"
        if [ -n "$prefixes" ]; then
            log -t vpnhide "applying interface prefixes: $prefixes"
            # shellcheck disable=SC2086
            "$CTL" iface_prefixes $prefixes
        else
            "$CTL" iface_prefixes
        fi

        # 3. Port rules
        local port_uids
        port_uids="$($SQLITE "$DB" "SELECT uid FROM app_protection WHERE portHiding = 1 AND uid != 0")"
        if [ -n "$port_uids" ]; then
            log -t vpnhide "applying port rules from DB"
            local port_args=""
            local mass_rules
            mass_rules="$($SQLITE "$DB" "SELECT startPort, endPort, protocol FROM mass_port_rules WHERE enabled = 1" | tr '|' ' ')"
            
            for U in $port_uids; do
                local app_rules
                app_rules="$($SQLITE "$DB" "SELECT pr.startPort, pr.endPort, pr.protocol FROM port_rules pr JOIN app_protection a ON pr.packageName = a.packageName AND pr.userId = a.userId WHERE a.uid = $U AND pr.enabled = 1" | tr '|' ' ')"
                
                local all_rules
                all_rules="$app_rules $mass_rules"
                local rule_count
                # count rules (each rule is 3 numbers)
                # shellcheck disable=SC2086
                rule_count=$(echo $all_rules | wc -w)
                rule_count=$((rule_count / 3))
                
                if [ "$rule_count" -gt 0 ]; then
                    port_args="$port_args $U $rule_count $all_rules"
                else
                    # Default: block all ports if no specific rules
                    port_args="$port_args $U 1 0 65535 2"
                fi
            done
            
            if [ -n "$port_args" ]; then
                # shellcheck disable=SC2086
                "$CTL" port_rules $port_args
            fi
        else
            "$CTL" port_rules
        fi
        
        # 4. Re-seed debug logging
        local ss_debug_logging="/data/system/vpnhide_debug_logging"
        if [ -f "$ss_debug_logging" ]; then
            # shellcheck disable=SC2046
            "$CTL" debug $(cat "$ss_debug_logging")
        fi
    fi
}

if [ -f "$DB" ] && [ -x "$SQLITE" ]; then
    log -t vpnhide "boot: database detected, invoking apply_all_rules_from_db"
    apply_all_rules_from_db
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
        else
            log -t vpnhide "package not found: $pkg"
        fi
    done < "$targets_file"
    
    # Clean up, sort, and return
    [ -n "$uids" ] && echo "$uids" | tr ' ' '\n' | grep -v '^$' | sort -u | xargs
}

# Resolve kmod targets via IOCTL
if [ -f "$KMOD_TARGETS" ]; then
    KMOD_UIDS="$(resolve_uids "$KMOD_TARGETS")"
    if [ -n "$KMOD_UIDS" ]; then
        log -t vpnhide "kmod: applying targets: $KMOD_UIDS"
        # Word splitting is intended to pass multiple UIDs as separate arguments
        # shellcheck disable=SC2086
        "$CTL" targets $KMOD_UIDS
        rc=$?
        
        count="$(echo "$KMOD_UIDS" | wc -w)"
        log -t vpnhide "kmod: successfully loaded $count target UIDs, rc=$rc"
    else
        log -t vpnhide "kmod: no UIDs resolved"
    fi
fi


# Resolve port targets (localhost blocker)
if [ -f "$PORT_TARGETS" ] && [ -f "$APPLY_PORTS" ]; then
    log -t vpnhide "ports: applying observers from $PORT_TARGETS"
    sh "$APPLY_PORTS"
fi


# Resolve lsposed targets → /data/system/vpnhide_uids.txt
mkdir -p /data/adb/vpnhide_lsposed 2>/dev/null

if [ -f "$LSPOSED_TARGETS" ]; then
    LSPOSED_UIDS="$(resolve_uids "$LSPOSED_TARGETS")"
    if [ -n "$LSPOSED_UIDS" ]; then
        echo "$LSPOSED_UIDS" | tr ' ' '\n' > "$SS_UIDS_FILE"
        chmod 644 "$SS_UIDS_FILE"
        chown root:system "$SS_UIDS_FILE"
        chcon u:object_r:system_data_file:s0 "$SS_UIDS_FILE" 2>/dev/null

        count="$(echo "$LSPOSED_UIDS" | wc -w)"
        log -t vpnhide "lsposed: wrote $count UIDs to $SS_UIDS_FILE"
    else
        echo > "$SS_UIDS_FILE"
        chmod 644 "$SS_UIDS_FILE"
        chown root:system "$SS_UIDS_FILE"
        log -t vpnhide "lsposed: no UIDs resolved"
    fi
fi

# Migrate pre-PR files written by older versions
for f in "$SS_UIDS_FILE" \
         /data/system/vpnhide_hidden_pkgs.txt \
         /data/system/vpnhide_observer_uids.txt; do
    if [ -f "$f" ]; then
        chmod 644 "$f"
        chown root:system "$f"
        chcon u:object_r:system_data_file:s0 "$f" 2>/dev/null
    fi
done

# Re-seed debug logging
SS_DEBUG_LOGGING="/data/system/vpnhide_debug_logging"
if [ -f "$SS_DEBUG_LOGGING" ]; then
    # shellcheck disable=SC2046
    "$CTL" debug $(cat "$SS_DEBUG_LOGGING")
fi

log -t vpnhide "service.sh background monitoring daemon starting in foreground"

IP_FILE="/data/system/vpnhide_physical_ip"

# Initialize physical IP file for system_server write access
if [ ! -f "$IP_FILE" ]; then
    echo "none none" > "$IP_FILE"
fi
chown system:system "$IP_FILE"
chmod 666 "$IP_FILE"
chcon u:object_r:system_data_file:s0 "$IP_FILE" 2>/dev/null

last_ips=""
while true; do
    if [ -f "$IP_FILE" ]; then
        current_ips="$(cat "$IP_FILE" 2>/dev/null)"
        if [ "$current_ips" != "$last_ips" ] && [ -n "$current_ips" ]; then
            ipv4="$(echo "$current_ips" | awk '{print $1}')"
            ipv6="$(echo "$current_ips" | awk '{print $2}')"
            
            # Default empty/invalid to none
            [ -n "$ipv4" ] || ipv4="none"
            [ -n "$ipv6" ] || ipv6="none"
            
            if "$CTL" set_spoof_ip "$ipv4" "$ipv6" 2>/dev/null; then
                log -t vpnhide "daemon: applied physical IP from file: IPv4=$ipv4, IPv6=$ipv6"
                last_ips="$current_ips"
            else
                log -t vpnhide "daemon: failed to apply physical IP to kernel"
                sleep 1
                continue
            fi
        fi
    fi
    sleep 2
done