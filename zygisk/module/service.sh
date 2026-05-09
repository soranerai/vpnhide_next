#!/system/bin/sh
# Copies zygisk targets to module dir and resolves lsposed targets at boot.
# zygisk targets → module dir (read by Zygisk via get_module_dir() fd)
# lsposed targets → /data/system/vpnhide_uids.txt

ZYGISK_TARGETS="/data/adb/vpnhide_zygisk/targets.txt"
ZYGISK_DEBUG_LOGGING="/data/adb/vpnhide_zygisk/debug_logging"
LSPOSED_TARGETS="/data/adb/vpnhide_lsposed/targets.txt"
MODULE_DIR="${0%/*}"
SS_UIDS_FILE="/data/system/vpnhide_uids.txt"

# Copy zygisk targets to module dir so Zygisk can read via get_module_dir() fd.
if [ -f "$ZYGISK_TARGETS" ]; then
    cp "$ZYGISK_TARGETS" "$MODULE_DIR/targets.txt" 2>/dev/null
fi

# Copy the persistent debug-logging flag into the module dir, same
# pattern as targets.txt above — the .so reads it via the module dir
# fd on every fork. The persistent file lives outside the module dir
# so it survives module reinstall; this re-seed restores the module-
# dir copy after a reinstall, before the user opens the app.
if [ -f "$ZYGISK_DEBUG_LOGGING" ]; then
    cp "$ZYGISK_DEBUG_LOGGING" "$MODULE_DIR/debug_logging" 2>/dev/null
fi

# Wait until PackageManager has actually indexed user-installed apps.
# `pm list packages` starts responding very early in boot but returns
# only system packages for several more seconds — if we resolve during
# that window, `dev.soranerai.vpnhidenext` (and any other user-installed
# target) silently drops from the UID file and the LSPosed hook caches
# an empty target set for the rest of the session. Gate on our own
# package being visible, with a 60s budget.
for i in $(seq 1 60); do
    if pm list packages -U 2>/dev/null | grep -q "^package:dev.soranerai.vpnhidenext "; then
        break
    fi
    sleep 1
done

# Migration: if lsposed targets don't exist yet, seed from zygisk targets
if [ ! -f "$LSPOSED_TARGETS" ] && [ -f "$ZYGISK_TARGETS" ]; then
    mkdir -p /data/adb/vpnhide_lsposed 2>/dev/null
    cp "$ZYGISK_TARGETS" "$LSPOSED_TARGETS"
    log -t vpnhide "migrated zygisk targets to lsposed targets"
fi

# Get all packages with UIDs across every profile in one call.
# `--user all` emits comma-separated UIDs per package for apps present
# in multiple profiles (e.g. work profile) so each profile's copy ends
# up in vpnhide_uids.txt.
ALL_PACKAGES="$(pm list packages -U --user all 2>/dev/null)"

# resolve_uids <targets_file> — prints one UID per line to stdout,
# splitting comma-separated UIDs so every profile gets its own entry.
resolve_uids() {
    local targets_file="$1"
    [ -f "$targets_file" ] || return
    local uids=""
    while IFS= read -r line || [ -n "$line" ]; do
        pkg="$(echo "$line" | tr -d '[:space:]')"
        [ -z "$pkg" ] && continue
        case "$pkg" in \#*) continue ;; esac
        # Literal match on $1 — grep would treat dots in `pkg` as regex
        # wildcards. awk's `$1 == p` compares fields literally.
        uid_csv="$(echo "$ALL_PACKAGES" | awk -v p="package:${pkg}" '$1 == p { sub(/uid:/, "", $2); print $2; exit }')"
        if [ -n "$uid_csv" ]; then
            expanded="$(echo "$uid_csv" | tr ',' '\n')"
            if [ -z "$uids" ]; then uids="$expanded"; else uids="${uids}
${expanded}"; fi
        else
            log -t vpnhide "package not found: $pkg"
        fi
    done < "$targets_file"
    [ -n "$uids" ] && echo "$uids"
}

# Resolve lsposed targets → /data/system/vpnhide_uids.txt
# Create persist dir if needed (for first-time installs)
mkdir -p /data/adb/vpnhide_lsposed 2>/dev/null
if [ -f "$LSPOSED_TARGETS" ]; then
    LSPOSED_UIDS="$(resolve_uids "$LSPOSED_TARGETS")"
    # Mode 0640 + group=system: system_server (UID 1000, in group `system`)
    # reads via the group bit; untrusted apps fall to "other" and get EACCES.
    # Default 0644 was a fingerprint vector — `/data/system/` itself is mode
    # 0775 traversable by untrusted, so any o+r file is enumerable + readable.
    if [ -n "$LSPOSED_UIDS" ]; then
        echo "$LSPOSED_UIDS" > "$SS_UIDS_FILE"
        chmod 640 "$SS_UIDS_FILE"
        chown root:system "$SS_UIDS_FILE"
        chcon u:object_r:system_data_file:s0 "$SS_UIDS_FILE" 2>/dev/null
        count="$(echo "$LSPOSED_UIDS" | wc -l)"
        log -t vpnhide "zygisk: wrote $count lsposed UIDs to $SS_UIDS_FILE"
    else
        echo > "$SS_UIDS_FILE"
        chmod 640 "$SS_UIDS_FILE"
        chown root:system "$SS_UIDS_FILE"
        log -t vpnhide "zygisk: no lsposed UIDs resolved"
    fi
fi

# Migrate pre-PR files written by older versions with mode 0644: any
# vpnhide_*.txt the lsposed app may have left in /data/system/. Touch
# only files that already exist; don't create new ones here.
for f in "$SS_UIDS_FILE" \
         /data/system/vpnhide_hidden_pkgs.txt \
         /data/system/vpnhide_observer_uids.txt; do
    if [ -f "$f" ]; then
        chmod 640 "$f"
        chown root:system "$f"
        chcon u:object_r:system_data_file:s0 "$f" 2>/dev/null
    fi
done
