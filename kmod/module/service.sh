#!/system/bin/sh
# Resolves package names → UIDs for kmod and lsposed at boot.
# kmod targets → /proc/vpnhide_targets
# lsposed targets → /data/system/vpnhide_uids.txt

KMOD_TARGETS="/data/adb/vpnhide_kmod/targets.txt"
KMOD_DIRECT_TARGETS="/data/adb/vpnhide_kmod/direct_targets.txt"
LSPOSED_TARGETS="/data/adb/vpnhide_lsposed/targets.txt"
SS_UIDS_FILE="/data/system/vpnhide_uids.txt"

# Get the directory where the script is located
MODDIR="${0%/*}"
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

if [ ! -c "$DEV_NODE" ]; then
    log -t vpnhide "kernel module control node not found, skipping kmod UID resolution"
fi

# Migration: if lsposed targets don't exist yet, seed from kmod targets
if [ ! -f "$LSPOSED_TARGETS" ] && [ -f "$KMOD_TARGETS" ]; then
    cp "$KMOD_TARGETS" "$LSPOSED_TARGETS"
    log -t vpnhide "migrated kmod targets to lsposed targets"
fi

# resolve_uids <targets_file> — prints a space-separated list of UIDs to stdout.
resolve_uids() {
    local targets_file="$1"
    [ -f "$targets_file" ] || return
    
    # Get a fresh list of all packages
    local all_pkgs
    all_pkgs="$(pm list packages -U --user all 2>/dev/null)"
    [ -n "$all_pkgs" ] || return

    local uids=""
    while IFS= read -r line || [ -n "$line" ]; do
        # ОПТИМИЗАЦИЯ: Убираем пробелы и спецсимволы средствами оболочки (без запуска 'tr')
        local pkg="${line//[[:space:]]/}"
        [ -z "$pkg" ] && continue
        case "$pkg" in \#*) continue ;; esac
        
        # ОПТИМИЗАЦИЯ: Экранируем точки для regex в awk (без запуска 'sed')
        local pkg_esc="${pkg//./\\.}"
        
        # ИСПРАВЛЕНИЕ: Убран 'exit' из awk, чтобы ловить все UID (включая Work Profiles / Dual Apps)
        local uid_csv
        uid_csv="$(echo "$all_pkgs" | awk -v p="^package:${pkg_esc}[ :]" '$0 ~ p { sub(/.*uid:/, "", $0); print $0 }')"
        
        if [ -n "$uid_csv" ]; then
            # Заменяем запятые и переносы строк на пробелы, чтобы собрать все UID
            local expanded
            expanded="$(echo "$uid_csv" | tr ',\n' '  ')"
            uids="$uids $expanded"
        else
            log -t vpnhide "package not found: $pkg"
        fi
    done < "$targets_file"
    
    # Убираем пустые элементы, сортируем, удаляем дубликаты и выстраиваем в строку
    [ -n "$uids" ] && echo "$uids" | tr ' ' '\n' | grep -v '^$' | sort -u | xargs
}

# Resolve kmod targets via IOCTL
if [ -f "$KMOD_TARGETS" ]; then
    KMOD_UIDS="$(resolve_uids "$KMOD_TARGETS")"
    if [ -n "$KMOD_UIDS" ]; then
        log -t vpnhide "kmod: applying targets: $KMOD_UIDS"
        # Word splitting will turn space-separated string into arguments for $CTL
        $CTL targets $KMOD_UIDS
        
        # ИСПРАВЛЕНИЕ: Подсчет слов ('-w') вместо подсчета строк ('-l')
        count="$(echo "$KMOD_UIDS" | wc -w)"
        log -t vpnhide "kmod: successfully loaded $count target UIDs"
    else
        log -t vpnhide "kmod: no UIDs resolved"
    fi
fi

# Resolve kmod direct bypass targets via IOCTL
if [ -f "$KMOD_DIRECT_TARGETS" ]; then
    DIRECT_UIDS="$(resolve_uids "$KMOD_DIRECT_TARGETS")"
    if [ -n "$DIRECT_UIDS" ]; then
        log -t vpnhide "kmod-direct: applying targets: $DIRECT_UIDS"
        $CTL direct $DIRECT_UIDS
        
        # ИСПРАВЛЕНИЕ: Подсчет слов ('-w') вместо подсчета строк ('-l')
        count="$(echo "$DIRECT_UIDS" | wc -w)"
        log -t vpnhide "kmod-direct: successfully loaded $count target UIDs"
    else
        log -t vpnhide "kmod-direct: no UIDs resolved"
    fi
fi

# Background monitoring: find active physical interface and tell the kernel
(
    TRIGGER="/tmp/vpnhide_route_trigger"
    rm -f "$TRIGGER"

    (
        while true; do
            ip monitor link address route | while read -r line; do
                touch "$TRIGGER"
            done
            sleep 1
        done
    ) &

    last_idx=""
    heartbeat_counter=0
    HEARTBEAT_MAX=75 

    while true; do
        if [ -f "$TRIGGER" ] || [ -z "$last_idx" ] || [ "$heartbeat_counter" -ge "$HEARTBEAT_MAX" ]; then
            rm -f "$TRIGGER"
            heartbeat_counter=0

            sleep 0.3
            
            phys_iface=""

            net_id=$(dumpsys connectivity | grep "Active default network:" | grep -oE "[0-9]+")
            
            if [ -n "$net_id" ]; then
                phys_iface=$(dumpsys connectivity | grep -E "network\{$net_id\}|NetID.*$net_id" | grep -oE "InterfaceName: [^ ]+" | head -n1 | awk -F': ' '{print $2}' | tr -d '},')
            fi
    
            if [ -z "$phys_iface" ]; then
                phys_iface=$(ip route show table all | grep "default via" | grep -vE "tun|wg|dummy|p2p|ccmni2" | head -n1 | awk '{print $5}')
            fi

            if [ -n "$phys_iface" ]; then
                phys_ifindex=$(cat "/sys/class/net/$phys_iface/ifindex" 2>/dev/null)
                
                if [ -n "$phys_ifindex" ]; then
                    $CTL phys "$phys_ifindex"
                    
                    if [ "$phys_ifindex" != "$last_idx" ]; then
                        log -t vpnhide "routing: active physical interface changed to $phys_iface ($phys_ifindex)"
                        last_idx="$phys_ifindex"
                    fi
                fi
            fi
        fi

        sleep 0.2 
        heartbeat_counter=$((heartbeat_counter + 1))
    done
) &

log -t vpnhide "service.sh background monitoring started"

# Resolve lsposed targets → /data/system/vpnhide_uids.txt
mkdir -p /data/adb/vpnhide_lsposed 2>/dev/null

if [ -f "$LSPOSED_TARGETS" ]; then
    LSPOSED_UIDS="$(resolve_uids "$LSPOSED_TARGETS")"
    if [ -n "$LSPOSED_UIDS" ]; then
        echo "$LSPOSED_UIDS" > "$SS_UIDS_FILE"
        chmod 640 "$SS_UIDS_FILE"
        chown root:system "$SS_UIDS_FILE"
        chcon u:object_r:system_data_file:s0 "$SS_UIDS_FILE" 2>/dev/null

        count="$(echo "$LSPOSED_UIDS" | wc -w)"
        log -t vpnhide "lsposed: wrote $count UIDs to $SS_UIDS_FILE"
    else
        echo > "$SS_UIDS_FILE"
        chmod 640 "$SS_UIDS_FILE"
        chown root:system "$SS_UIDS_FILE"
        log -t vpnhide "lsposed: no UIDs resolved"
    fi
fi

# Migrate pre-PR files written by older versions
for f in "$SS_UIDS_FILE" \
         /data/system/vpnhide_hidden_pkgs.txt \
         /data/system/vpnhide_observer_uids.txt; do
    if [ -f "$f" ]; then
        chmod 640 "$f"
        chown root:system "$f"
        chcon u:object_r:system_data_file:s0 "$f" 2>/dev/null
    fi
done

# Re-seed debug logging
SS_DEBUG_LOGGING="/data/system/vpnhide_debug_logging"
if [ -f "$SS_DEBUG_LOGGING" ]; then
    $CTL debug $(cat "$SS_DEBUG_LOGGING")
fi