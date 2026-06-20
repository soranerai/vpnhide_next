#!/system/bin/sh
SKIPUNZIP=0
MOD_VER="$(grep '^version=' "$MODPATH/module.prop" | cut -d= -f2)"
ui_print "- VPNHide Next (kernel) ${MOD_VER:-unknown}"
ui_print "- Installing kernel module to $MODPATH"

set_perm "$MODPATH/vpnhide_kmod.ko" 0 0 0644
set_perm "$MODPATH/vpnhide-ctl" 0 0 0755
set_perm "$MODPATH/sqlite3" 0 0 0755
set_perm "$MODPATH/sepolicy.rule" 0 0 0644

# Target database paths
SQLITE="$MODPATH/sqlite3"

# Legacy targets files to migrate
LEGACY_FILES_EXIST=0
if [ -f "/data/adb/vpnhide/targets.txt" ] || \
   [ -f "/data/adb/vpnhide_kmod/targets.txt" ] || \
   [ -f "/data/adb/vpnhide_lsposed/targets.txt" ] || \
   [ -f "/data/adb/vpnhide_ports/observers.txt" ]; then
    LEGACY_FILES_EXIST=1
fi

if [ "$LEGACY_FILES_EXIST" -eq 1 ]; then
    ui_print "- Legacy target files found, preparing migration..."

    # 1. Determine target database directories
    self_uid="$(pm list packages -U --user all 2>/dev/null | grep "^package:dev.soranerai.vpnhidenext " | awk '{print $2}' | sed 's/uid://' | tr ',' '\n' | head -n 1)"

    DB_DIRS=""
    if [ -n "$self_uid" ]; then
        DB_DIRS="$(find /data/user /data/user_de /data/data -maxdepth 4 -name "dev.soranerai.vpnhidenext" 2>/dev/null | sed 's|$|/databases|')"
    fi

    # If app is not installed yet, migrate to a temporary database in /data/adb/vpnhide_kmod
    if [ -z "$DB_DIRS" ]; then
        DB_DIRS="/data/adb/vpnhide_kmod"
        mkdir -p "$DB_DIRS"
        chmod 755 "$DB_DIRS"
    fi

    # Helper function to migrate legacy targets files
    migrate_targets() {
        local file="$1"
        local kmod="$2"
        local lsposed="$3"
        local port_hiding="$4"
        local db_file="$5"
        if [ -f "$file" ]; then
            ui_print "  * Migrating targets from $file to $db_file"
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

                local exists
                exists="$($SQLITE "$db_file" "SELECT COUNT(*) FROM app_protection WHERE packageName='$pkg' AND userId=$user" 2>/dev/null || echo 0)"
                if [ "$exists" -eq 0 ]; then
                    $SQLITE "$db_file" "INSERT INTO app_protection (packageName, userId, kmod, lsposed, portHiding, uid) VALUES ('$pkg', $user, $kmod, $lsposed, $port_hiding, 0)"
                else
                    $SQLITE "$db_file" "UPDATE app_protection SET kmod=max(kmod, $kmod), lsposed=max(lsposed, $lsposed), portHiding=max(portHiding, $port_hiding) WHERE packageName='$pkg' AND userId=$user"
                fi
            done < "$file"
        fi
    }

    for db_dir in $DB_DIRS; do
        [ -d "$db_dir" ] || mkdir -p "$db_dir"
        local db_file="$db_dir/vpnhide_database"

        # Create SQLite tables with correct Room v8 schema
        $SQLITE "$db_file" "CREATE TABLE IF NOT EXISTS app_protection (packageName TEXT NOT NULL, userId INTEGER NOT NULL DEFAULT 0, kmod INTEGER NOT NULL DEFAULT 0, lsposed INTEGER NOT NULL DEFAULT 0, portHiding INTEGER NOT NULL DEFAULT 0, uid INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(packageName, userId));"
        $SQLITE "$db_file" "CREATE TABLE IF NOT EXISTS port_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT NOT NULL, userId INTEGER NOT NULL DEFAULT 0, startPort INTEGER NOT NULL, endPort INTEGER NOT NULL, protocol INTEGER NOT NULL, label TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(packageName, userId) REFERENCES app_protection(packageName, userId) ON UPDATE NO ACTION ON DELETE CASCADE);"
        $SQLITE "$db_file" "CREATE INDEX IF NOT EXISTS index_port_rules_packageName_userId ON port_rules(packageName, userId);"
        $SQLITE "$db_file" "CREATE TABLE IF NOT EXISTS mass_port_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startPort INTEGER NOT NULL, endPort INTEGER NOT NULL, protocol INTEGER NOT NULL, label TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1);"
        $SQLITE "$db_file" "CREATE TABLE IF NOT EXISTS iface_prefixes (prefix TEXT NOT NULL, PRIMARY KEY(prefix));"
        $SQLITE "$db_file" "CREATE TABLE IF NOT EXISTS global_config (id TEXT NOT NULL, kernelHookMask INTEGER NOT NULL DEFAULT 4294967295, javaHookMask INTEGER NOT NULL DEFAULT 4294967295, debugLogging INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id));"
        $SQLITE "$db_file" "INSERT OR IGNORE INTO global_config (id, kernelHookMask, javaHookMask, debugLogging) VALUES ('default', 4294967295, 4294967295, 0);"

        # 2. Run migrations
        migrate_targets "/data/adb/vpnhide/targets.txt" 1 0 0 "$db_file"
        migrate_targets "/data/adb/vpnhide_kmod/targets.txt" 1 0 0 "$db_file"
        migrate_targets "/data/adb/vpnhide_lsposed/targets.txt" 0 1 0 "$db_file"
        migrate_targets "/data/adb/vpnhide_ports/observers.txt" 0 0 1 "$db_file"

        # Apply correct system permissions and SELinux label for the app's databases directory
        if [ -n "$self_uid" ] && [ "$db_dir" != "/data/adb/vpnhide_kmod" ]; then
            chmod 660 "$db_file"*
            chown "$self_uid:$self_uid" "$db_file"*
            restorecon -R "$db_dir" 2>/dev/null || true
        fi
    done

    # Cleanup migrated legacy files
    rm -f "/data/adb/vpnhide/targets.txt" 2>/dev/null
    rm -f "/data/adb/vpnhide_kmod/targets.txt" 2>/dev/null
    rm -f "/data/adb/vpnhide_lsposed/targets.txt" 2>/dev/null
    rm -f "/data/adb/vpnhide_ports/observers.txt" 2>/dev/null
fi

# Clean up obsolete legacy /data/system/vpnhide directories
rm -rf /data/system/vpnhide*

ui_print "- Pick apps via the VPNHide Next app."
