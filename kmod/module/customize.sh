#!/system/bin/sh
SKIPUNZIP=0
MOD_VER="$(grep '^version=' "$MODPATH/module.prop" | cut -d= -f2)"
ui_print "- VPNHide Next (kernel) ${MOD_VER:-unknown}"
ui_print "- Installing kernel module to $MODPATH"

set_perm "$MODPATH/vpnhide_kmod.ko" 0 0 0644
set_perm "$MODPATH/vpnhide-ctl" 0 0 0755
set_perm "$MODPATH/sepolicy.rule" 0 0 0644

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

    # Determine target JSON config paths
    self_uid="$(pm list packages -U --user all 2>/dev/null | grep "^package:dev.soranerai.vpnhidenext " | awk '{print $2}' | sed 's/uid://' | tr ',' '\n' | head -n 1)"

    JSON_DIRS=""
    if [ -n "$self_uid" ]; then
        JSON_DIRS="$(find /data/user /data/user_de /data/data -maxdepth 4 -name "dev.soranerai.vpnhidenext" 2>/dev/null | sed 's|$|/files|')"
    fi

    # If app is not installed yet, migrate to a temporary location
    if [ -z "$JSON_DIRS" ]; then
        JSON_DIRS="/data/adb/vpnhide_kmod"
        mkdir -p "$JSON_DIRS"
        chmod 755 "$JSON_DIRS"
    fi

    # We will collect all legacy targets and merge them
    # Format of temp storage: package_name userId kmod lsposed port_hiding
    TEMP_LIST="/tmp/vpnhide_legacy_list.txt"
    rm -f "$TEMP_LIST"

    parse_to_list() {
        local file="$1"
        local kmod="$2"
        local lsposed="$3"
        local port_hiding="$4"
        if [ -f "$file" ]; then
            ui_print "  * Parsing legacy targets from $file"
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
                echo "$pkg $user $kmod $lsposed $port_hiding" >> "$TEMP_LIST"
            done < "$file"
        fi
    }

    parse_to_list "/data/adb/vpnhide/targets.txt" 1 0 0
    parse_to_list "/data/adb/vpnhide_kmod/targets.txt" 1 0 0
    parse_to_list "/data/adb/vpnhide_lsposed/targets.txt" 0 1 0
    parse_to_list "/data/adb/vpnhide_ports/observers.txt" 0 0 1

    if [ -f "$TEMP_LIST" ]; then
        # Let's use awk to merge and format as JSON objects
        MERGED_APPS=$(awk '{ 
            key = $1"|"$2; 
            k[key] = (k[key] || $3); 
            l[key] = (l[key] || $4); 
            p[key] = (p[key] || $5); 
            pkg[key] = $1; 
            usr[key] = $2; 
        } END { 
            first = 1;
            for (key in pkg) {
                km = k[key] ? "true" : "false";
                lsp = l[key] ? "true" : "false";
                ph = p[key] ? "true" : "false";
                printf "%s{\"packageName\":\"%s\",\"userId\":%d,\"uid\":0,\"kmod\":%s,\"lsposed\":%s,\"portHiding\":%s}", 
                    (first ? "" : ","), pkg[key], usr[key], km, lsp, ph;
                first = 0;
            }
        }' "$TEMP_LIST")

        for json_dir in $JSON_DIRS; do
            [ -d "$json_dir" ] || mkdir -p "$json_dir"
            local json_file="$json_dir/vpnhide_config.json"
            
            echo "{\"globalConfig\":{\"id\":\"default\",\"kernelHookMask\":4294967295,\"javaHookMask\":4294967295,\"debugLogging\":0},\"apps\":[$MERGED_APPS],\"portRules\":[],\"massPortRules\":[],\"ifacePrefixes\":[]}" > "$json_file"

            # Apply correct permissions
            if [ -n "$self_uid" ] && [ "$json_dir" != "/data/adb/vpnhide_kmod" ]; then
                chmod 660 "$json_file"
                chown "$self_uid:$self_uid" "$json_file"
                restorecon -R "$json_dir" 2>/dev/null || true
            fi
        done
        rm -f "$TEMP_LIST"
    fi

    # Cleanup migrated legacy files
    rm -f "/data/adb/vpnhide/targets.txt" 2>/dev/null
    rm -f "/data/adb/vpnhide_kmod/targets.txt" 2>/dev/null
    rm -f "/data/adb/vpnhide_lsposed/targets.txt" 2>/dev/null
    rm -f "/data/adb/vpnhide_ports/observers.txt" 2>/dev/null
fi

# Clean up obsolete legacy /data/system/vpnhide directories
rm -rf /data/system/vpnhide*

ui_print "- Pick apps via the VPNHide Next app."
