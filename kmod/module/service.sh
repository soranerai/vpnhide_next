#!/system/bin/sh
# Resolves and applies rules from SQLite database at boot.

MODDIR="$(cd "$(dirname "$0")" && pwd)"
CTL="$MODDIR/vpnhide-ctl"
DEV_NODE="/dev/vpnhide_ctrl"

log -t vpnhide "service.sh starting: MODDIR=$MODDIR"

# Wait for kernel module control node to be ready
for i in $(seq 1 10); do
    [ -c "$DEV_NODE" ] && break
    lsmod | grep -q vpnhide_kmod && [ -c "$DEV_NODE" ] && break
    sleep 1
done
chmod +x "$CTL"

# Wait until PackageManager has actually indexed user-installed apps.
for i in $(seq 1 60); do
    if pm list packages -U 2>/dev/null | grep -q "^package:dev.soranerai.vpnhidenext "; then
        break
    fi
    sleep 1
done

# Give PM a moment to settle after the app becomes visible
sleep 2

# Verify the module is actually there.
for i in $(seq 1 10); do
    [ -c "$DEV_NODE" ] && break
    lsmod | grep -q vpnhide_kmod && [ -c "$DEV_NODE" ] && break
    sleep 1
done

if [ ! -c "$DEV_NODE" ]; then
    log -t vpnhide "kernel module control node not found, skipping kmod rules application"
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
        
        # Resolve and populate missing UIDs in the database at boot
        local pm_list
        pm_list="$(pm list packages -U --user all 2>/dev/null)"
        if [ -n "$pm_list" ]; then
            local apps
            apps="$($SQLITE "$DB" "SELECT packageName, userId FROM app_protection" 2>/dev/null)"
            for app_row in $apps; do
                local pkg
                local user
                pkg="$(echo "$app_row" | cut -d'|' -f1)"
                user="$(echo "$app_row" | cut -d'|' -f2)"
                [ -n "$pkg" ] || continue
                
                local resolved_uid
                resolved_uid="$(echo "$pm_list" | grep "^package:$pkg " | awk '{print $2}' | sed 's/uid://' | tr ',' '\n' | while read -r u; do
                    local u_id=$((u / 100000))
                    if [ "$u_id" -eq "$user" ]; then
                        echo "$u"
                        break
                    fi
                done)"
                
                if [ -n "$resolved_uid" ] && [ "$resolved_uid" -gt 0 ]; then
                    $SQLITE "$DB" "UPDATE app_protection SET uid = $resolved_uid WHERE packageName = '$pkg' AND userId = $user"
                fi
            done
        fi
        
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

        # 5. Hook masks
        local kernel_mask
        kernel_mask="$($SQLITE "$DB" "SELECT kernelHookMask FROM global_config WHERE id = 'default'" | xargs)"
        if [ -n "$kernel_mask" ]; then
            log -t vpnhide "boot: applying active hooks mask from DB: $kernel_mask"
            "$CTL" active_hooks "$kernel_mask"
        fi
    fi
}

if [ -f "$DB" ] && [ -x "$SQLITE" ]; then
    log -t vpnhide "boot: database detected, invoking apply_all_rules_from_db"
    apply_all_rules_from_db
else
    log -t vpnhide "boot: database or sqlite3 not found, no rules applied yet"
fi

# Apply autodisable / testing active hooks mask if configured
AUTODISABLE_FILE="/data/adb/vpnhide_kmod/autodisable_mask.txt"
if [ -f "$AUTODISABLE_FILE" ] && [ -x "$CTL" ]; then
    MASK=$(tr -d '\r\n' < "$AUTODISABLE_FILE")
    if [ -n "$MASK" ]; then
        log -t vpnhide "boot: applying active hooks mask $MASK"
        "$CTL" active_hooks "$MASK"
    fi
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