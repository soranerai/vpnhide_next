#!/system/bin/sh
SKIPUNZIP=0
MOD_VER="$(grep '^version=' "$MODPATH/module.prop" | cut -d= -f2)"
ui_print "- VPNHide Next (kernel) ${MOD_VER:-unknown}"
ui_print "- Installing kernel module to $MODPATH"

set_perm "$MODPATH/vpnhide_kmod.ko" 0 0 0644
set_perm "$MODPATH/vpnhide-ctl" 0 0 0755
set_perm "$MODPATH/sqlite3" 0 0 0755

# Target database paths
DB_DIR="/data/system/vpnhide"
DB="$DB_DIR/vpnhide_config.db"
SQLITE="$MODPATH/sqlite3"

if [ ! -f "$DB" ]; then
    ui_print "- Database not found, preparing migration..."
    mkdir -p "$DB_DIR"
    chmod 755 "$DB_DIR"
    chown system:system "$DB_DIR"

    # 1. Create SQLite tables
    $SQLITE "$DB" "CREATE TABLE IF NOT EXISTS app_protection (packageName TEXT NOT NULL, userId INTEGER NOT NULL DEFAULT 0, kmod INTEGER NOT NULL DEFAULT 0, lsposed INTEGER NOT NULL DEFAULT 0, portHiding INTEGER NOT NULL DEFAULT 0, uid INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(packageName, userId));"
    $SQLITE "$DB" "CREATE TABLE IF NOT EXISTS port_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT NOT NULL, userId INTEGER NOT NULL DEFAULT 0, startPort INTEGER NOT NULL, endPort INTEGER NOT NULL, protocol INTEGER NOT NULL, label TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(packageName, userId) REFERENCES app_protection(packageName, userId) ON UPDATE NO ACTION ON DELETE CASCADE);"
    $SQLITE "$DB" "CREATE INDEX IF NOT EXISTS index_port_rules_packageName_userId ON port_rules(packageName, userId);"
    $SQLITE "$DB" "CREATE TABLE IF NOT EXISTS mass_port_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startPort INTEGER NOT NULL, endPort INTEGER NOT NULL, protocol INTEGER NOT NULL, label TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1);"
    $SQLITE "$DB" "CREATE TABLE IF NOT EXISTS iface_prefixes (prefix TEXT NOT NULL, PRIMARY KEY(prefix));"
    $SQLITE "$DB" "CREATE TABLE IF NOT EXISTS global_config (id TEXT NOT NULL, kernelHookMask INTEGER NOT NULL DEFAULT 4294967295, javaHookMask INTEGER NOT NULL DEFAULT 4294967295, PRIMARY KEY(id));"
    $SQLITE "$DB" "INSERT OR IGNORE INTO global_config (id, kernelHookMask, javaHookMask) VALUES ('default', 4294967295, 4294967295);"

    # Helper function to migrate legacy targets files
    migrate_targets() {
        local file="$1"
        local kmod="$2"
        local lsposed="$3"
        local port_hiding="$4"
        if [ -f "$file" ]; then
            ui_print "  * Migrating targets from $file"
            while IFS= read -r line || [ -n "$line" ]; do
                line=$(echo "$line" | sed 's/#.*//' | xargs)
                [ -n "$line" ] || continue
                local pkg=""
                local user=0
                if echo "$line" | grep -q ":"; then
                    pkg=$(echo "$line" | cut -d: -f1)
                    user=$(echo "$line" | cut -d: -f2)
                else
                    pkg="$line"
                fi
                # Check duplicate
                local exists
                exists="$($SQLITE "$DB" "SELECT COUNT(*) FROM app_protection WHERE packageName='$pkg' AND userId=$user")"
                if [ "$exists" -eq 0 ]; then
                    $SQLITE "$DB" "INSERT INTO app_protection (packageName, userId, kmod, lsposed, portHiding, uid) VALUES ('$pkg', $user, $kmod, $lsposed, $port_hiding, 0)"
                else
                    $SQLITE "$DB" "UPDATE app_protection SET kmod=max(kmod, $kmod), lsposed=max(lsposed, $lsposed), portHiding=max(portHiding, $port_hiding) WHERE packageName='$pkg' AND userId=$user"
                fi
            done < "$file"
        fi
    }

    # 2. Run migrations
    migrate_targets "/data/adb/vpnhide/targets.txt" 1 0 0
    migrate_targets "/data/adb/vpnhide_kmod/targets.txt" 1 0 0
    migrate_targets "/data/adb/vpnhide_lsposed/targets.txt" 0 1 0
    migrate_targets "/data/adb/vpnhide_ports/observers.txt" 0 0 1
fi

# Apply correct system permissions and SELinux label
if [ -f "$DB" ]; then
    chmod 644 "$DB"*
    chown system:system "$DB"*
    chcon u:object_r:system_data_file:s0 "$DB"* 2>/dev/null || true
fi

# Apply mirror copy to the manager app databases directory if it is already installed
self_uid="$(pm list packages -U --user all 2>/dev/null | grep "^package:dev.soranerai.vpnhidenext " | awk '{print $2}' | sed 's/uid://' | tr ',' '\n' | head -n 1)"
if [ -n "$self_uid" ] && [ -f "$DB" ]; then
    ui_print "- Manager app detected (UID $self_uid), syncing private database..."
    find /data/user /data/data -maxdepth 4 -name "dev.soranerai.vpnhidenext" 2>/dev/null | while read -r p; do
        db_dir="$p/databases"
        mkdir -p "$db_dir"
        app_db="$db_dir/vpnhide_database"
        
        cp "$DB" "$app_db"
        if [ -f "$DB-wal" ]; then
            cp "$DB-wal" "$app_db-wal" || true
        fi
        if [ -f "$DB-shm" ]; then
            cp "$DB-shm" "$app_db-shm" || true
        fi
        
        chmod 660 "$app_db"*
        chown "$self_uid:$self_uid" "$app_db"*
        restorecon -R "$db_dir" 2>/dev/null || true
    done
fi

ui_print "- Pick apps via the VPNHide Next app."
