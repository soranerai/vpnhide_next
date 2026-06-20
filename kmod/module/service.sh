#!/system/bin/sh
# Resolves and applies rules from SQLite database at boot.

MODDIR="$(cd "$(dirname "$0")" && pwd)"
CTL="$MODDIR/vpnhide-ctl"
DEV_NODE="/dev/vpnhide_ctrl"

LOG_FILE="/data/adb/vpnhide_kmod/service.log"
mkdir -p "/data/adb/vpnhide_kmod"
echo "=== service.sh boot start: $(date) ===" > "$LOG_FILE"

log_msg() {
    log -t vpnhide "$1"
    echo "$(date '+%Y-%m-%d %H:%M:%S') [service.sh] $1" >> "$LOG_FILE"
}

log_msg "service.sh starting: MODDIR=$MODDIR"

# Wait for kernel module control node to be ready
for i in $(seq 1 10); do
    [ -c "$DEV_NODE" ] && break
    lsmod | grep -q vpnhide_kmod && [ -c "$DEV_NODE" ] && break
    sleep 1
done
chmod +x "$CTL"
if [ -c "$DEV_NODE" ]; then
    chown root:system "$DEV_NODE"
    chmod 0660 "$DEV_NODE"
fi

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

if [ -c "$DEV_NODE" ]; then
    chown root:system "$DEV_NODE"
    chmod 0660 "$DEV_NODE"
else
    log_msg "kernel module control node not found, skipping kmod rules application"
fi

# Check for fallback migrated database and move it if the app is installed
TEMP_DB="/data/adb/vpnhide_kmod/vpnhide_database"
if [ -f "$TEMP_DB" ]; then
    self_uid="$(pm list packages -U --user all 2>/dev/null | grep "^package:dev.soranerai.vpnhidenext " | awk '{print $2}' | sed 's/uid://' | tr ',' '\n' | head -n 1)"
    if [ -n "$self_uid" ]; then
        log_msg "manager app detected (UID $self_uid), promoting fallback database..."
        find /data/user /data/user_de /data/data -maxdepth 4 -name "dev.soranerai.vpnhidenext" 2>/dev/null | while read -r p; do
            db_dir="$p/databases"
            mkdir -p "$db_dir"
            app_db="$db_dir/vpnhide_database"

            log_msg "copying $TEMP_DB to $app_db"
            cp "$TEMP_DB" "$app_db"
            if [ -f "$TEMP_DB-wal" ]; then
                cp "$TEMP_DB-wal" "$app_db-wal" || true
            fi
            if [ -f "$TEMP_DB-shm" ]; then
                cp "$TEMP_DB-shm" "$app_db-shm" || true
            fi

            chmod 660 "$app_db"*
            chown "$self_uid:$self_uid" "$app_db"*
            restorecon -R "$db_dir" 2>/dev/null || true
        done
        rm -f "$TEMP_DB"*
    fi
fi

# Detect SQLite database
DB="/data/data/dev.soranerai.vpnhidenext/databases/vpnhide_database"
if [ ! -f "$DB" ]; then
    DB="/data/user_de/0/dev.soranerai.vpnhidenext/databases/vpnhide_database"
fi
SQLITE="$MODDIR/sqlite3"
[ -f "$SQLITE" ] || SQLITE="/system/bin/sqlite3"
[ -f "$SQLITE" ] || SQLITE="/data/adb/magisk/sqlite3"
[ -f "$SQLITE" ] || SQLITE="$(which sqlite3 2>/dev/null)"

apply_all_rules_from_db() {
    if [ -f "$DB" ] && [ -x "$SQLITE" ]; then
        log_msg "applying rules from DB..."
        
        # Resolve and populate missing UIDs in the database at boot
        local pm_list
        pm_list="$(pm list packages -U --user all 2>/dev/null)"
        if [ -n "$pm_list" ]; then
            local apps
            apps="$($SQLITE "$DB" "SELECT packageName, userId FROM app_protection WHERE userId is null" 2>/dev/null)"
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
            log_msg "applying VPN targets: $kmod_uids"
            # shellcheck disable=SC2086
            "$CTL" targets $kmod_uids
        else
            "$CTL" targets
        fi

        # 1b. LSPosed targets
        local lsposed_uids
        lsposed_uids="$($SQLITE "$DB" "SELECT uid FROM app_protection WHERE lsposed = 1 AND uid != 0" 2>/dev/null | xargs)"
        if [ -n "$self_uid" ]; then
            lsposed_uids="$lsposed_uids $self_uid"
        fi

        if [ -n "$lsposed_uids" ]; then
            lsposed_uids="$(echo "$lsposed_uids" | tr ' ' '\n' | grep -v '^$' | sort -u | xargs)"
            log_msg "applying LSPosed targets: $lsposed_uids"
            # shellcheck disable=SC2086
            "$CTL" lsposed_targets $lsposed_uids
        else
            "$CTL" lsposed_targets
        fi

        # 2. Interface prefixes
        local prefixes
        prefixes="$($SQLITE "$DB" "SELECT prefix FROM iface_prefixes" | xargs)"
        if [ -n "$prefixes" ]; then
            log_msg "applying interface prefixes: $prefixes"
            # shellcheck disable=SC2086
            "$CTL" iface_prefixes $prefixes
        else
            "$CTL" iface_prefixes
        fi

        # 3. Port rules
        local port_uids
        port_uids="$($SQLITE "$DB" "SELECT uid FROM app_protection WHERE portHiding = 1 AND uid != 0")"
        if [ -n "$port_uids" ]; then
            log_msg "applying port rules from DB"
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
        
        # 4. Re-seed debug logging from DB
        local debug_val
        debug_val="$($SQLITE "$DB" "SELECT debugLogging FROM global_config WHERE id = 'default'" 2>/dev/null | xargs)"
        if [ "$debug_val" = "1" ]; then
            log_msg "boot: applying debug logging from DB: 1"
            "$CTL" debug 1
        else
            log_msg "boot: applying debug logging from DB: 0"
            "$CTL" debug 0
        fi

        # 5. Hook masks
        local kernel_mask
        kernel_mask="$($SQLITE "$DB" "SELECT kernelHookMask FROM global_config WHERE id = 'default'" 2>/dev/null | xargs)"
        if [ -n "$kernel_mask" ]; then
            log_msg "boot: applying active hooks mask from DB: $kernel_mask"
            "$CTL" active_hooks "$kernel_mask"
        fi
        local java_mask
        java_mask="$($SQLITE "$DB" "SELECT javaHookMask FROM global_config WHERE id = 'default'" 2>/dev/null | xargs)"
        if [ -n "$java_mask" ]; then
            log_msg "boot: applying java hooks mask from DB: $java_mask"
            "$CTL" java_hooks "$java_mask"
        fi
    fi
}

if [ -f "$DB" ] && [ -x "$SQLITE" ]; then
    log_msg "boot: database detected, invoking apply_all_rules_from_db"
    apply_all_rules_from_db
else
    log_msg "boot: database or sqlite3 not found, no rules applied yet"
    log_msg "database=$DB"
    log_msg "sqlite=$SQLITE"
fi



DAEMON="$MODDIR/vpnhide-daemon"
chmod +x "$DAEMON"

# Start the event-driven C daemon in the background
"$DAEMON" >/data/adb/vpnhide_kmod/daemon.log 2>&1 &