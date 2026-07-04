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
    chcon u:object_r:null_device:s0 "$DEV_NODE"
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

# Detect JSON config file in Device Protected Storage files directory
JSON_CONF="/data/user_de/0/dev.soranerai.vpnhidenext/files/vpnhide_config.json"

apply_all_rules_from_json() {
    if [ -f "$JSON_CONF" ]; then
        log_msg "applying rules from JSON..."
        # Resolve the app itself (dev.soranerai.vpnhidenext) UID and pass it to load
        local self_uid
        self_uid="$(pm list packages -U --user all 2>/dev/null | grep "^package:dev.soranerai.vpnhidenext " | awk '{print $2}' | sed 's/uid://' | tr ',' '\n' | head -n 1)"
        if [ -n "$self_uid" ]; then
            log_msg "applying JSON config with self UID $self_uid"
            "$CTL" load "$JSON_CONF" "$self_uid"
        else
            log_msg "applying JSON config without self UID"
            "$CTL" load "$JSON_CONF"
        fi
    else
        log_msg "JSON config file not found"
    fi
}

if [ -f "$JSON_CONF" ]; then
    log_msg "boot: JSON config detected, invoking apply_all_rules_from_json"
    apply_all_rules_from_json
else
    log_msg "boot: JSON config not found, no rules applied yet"
    log_msg "json_config=$JSON_CONF"
fi

DAEMON="$MODDIR/vpnhide-daemon"
chmod +x "$DAEMON"

# Start the event-driven C daemon in the background
"$DAEMON" >/data/adb/vpnhide_kmod/daemon.log 2>&1 &