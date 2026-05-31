#!/system/bin/sh
# Runs early in boot, before apps start. Loads the kernel module and
# records load_status so the app can explain *why* the module didn't
# come up (wrong GKI variant, missing kretprobes, etc.) without having
# to guess from a missing /proc entry.

MODDIR="${0%/*}"
KO="$MODDIR/vpnhide_kmod.ko"
MODULE_PROP="$MODDIR/module.prop"
STATUS_DIR="/data/adb/vpnhide_kmod"
STATUS_FILE="$STATUS_DIR/load_status"
DMESG_FILE="$STATUS_DIR/load_dmesg"

# Already loaded on a previous trigger in this boot — nothing to do.
if grep -q vpnhide_kmod /proc/modules 2>/dev/null; then
    exit 0
fi

# Crash detection and automatic hook mitigation
CRASH_DIR="/data/adb/vpnhide_kmod"
CRASH_FILE="$CRASH_DIR/last_crash.txt"
AUTODISABLE_FILE="$CRASH_DIR/autodisable_mask.txt"

detect_previous_crash() {
    # Scan standard pstore and last_kmsg locations
    local log_files="/sys/fs/pstore/console-ramoops /sys/fs/pstore/console-ramoops-0 /sys/fs/pstore/dmesg-ramoops-0 /sys/fs/pstore/dmesg-ramoops-1 /proc/last_kmsg"
    for f in $log_files; do
        # resolved in case of wildcards or spaces
        # shellcheck disable=SC2086
        for resolved_file in $f; do
            [ -f "$resolved_file" ] || continue
            # Check if the log contains a panic from our module (stack trace symbol)
            if grep -q -F "[vpnhide_kmod]" "$resolved_file" 2>/dev/null; then
                local crash_traces
                crash_traces=$(grep -B 15 -A 15 -F "[vpnhide_kmod]" "$resolved_file" 2>/dev/null | head -n 40)
                if [ -n "$crash_traces" ]; then
                    mkdir -p "$CRASH_DIR"
                    {
                        echo "CRASH_DETECTED"
                        echo "File: $resolved_file"
                        echo "Trace:"
                        echo "$crash_traces"
                    } > "$CRASH_FILE"
                    
                    # Identify which hook caused the crash
                    local hook_index=-1
                    if echo "$crash_traces" | grep -q "dev_ioctl"; then hook_index=0
                    elif echo "$crash_traces" | grep -q "sock_ioctl"; then hook_index=1
                    elif echo "$crash_traces" | grep -q "rtnl_fill"; then hook_index=2
                    elif echo "$crash_traces" | grep -q "inet6_fill"; then hook_index=3
                    elif echo "$crash_traces" | grep -q "inet_fill"; then hook_index=4
                    elif echo "$crash_traces" | grep -q "fib_route"; then hook_index=5
                    elif echo "$crash_traces" | grep -q "ipv6_route"; then hook_index=6
                    elif echo "$crash_traces" | grep -q "fib_dump"; then hook_index=7
                    elif echo "$crash_traces" | grep -q "fib_rule_fill"; then hook_index=8
                    elif echo "$crash_traces" | grep -q "rt6_fill"; then hook_index=9
                    elif echo "$crash_traces" | grep -q "rt_fill"; then hook_index=10
                    elif echo "$crash_traces" | grep -q "sock_setsockopt"; then hook_index=11
                    elif echo "$crash_traces" | grep -q "sock_getsockopt"; then hook_index=12
                    elif echo "$crash_traces" | grep -q "socket_connect"; then hook_index=13
                    elif echo "$crash_traces" | grep -q "inet_getname"; then hook_index=14
                    elif echo "$crash_traces" | grep -q "inet6_getname"; then hook_index=15
                    elif echo "$crash_traces" | grep -q "socket_bind"; then hook_index=16
                    fi
                    
                    if [ "$hook_index" -ge 0 ]; then
                        echo "crashed_hook=$hook_index" >> "$CRASH_FILE"
                        
                        local current_mask=4294967295
                        if [ -f "$AUTODISABLE_FILE" ]; then
                            current_mask=$(cat "$AUTODISABLE_FILE")
                        fi
                        
                        # Clear the bit
                        local new_mask=$((current_mask & ~(1 << hook_index)))
                        echo "$new_mask" > "$AUTODISABLE_FILE"
                    fi
                    return 0
                fi
            fi
        done
    done
}

# Run previous crash detection
detect_previous_crash

mkdir -p "$STATUS_DIR"

# Collapse newlines and tabs in a value so the key=value parser in the
# app stays line-based even when insmod stderr is multi-line.
sanitize() {
    printf '%s' "$1" | tr '\n\r\t' '   ' | sed 's/  */ /g'
}

BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
UNAME_R=$(uname -r 2>/dev/null)
NOW=$(date +%s 2>/dev/null)
GKI_VARIANT=$(grep '^gkiVariant=' "$MODULE_PROP" 2>/dev/null | cut -d= -f2-)
KMOD_VERSION=$(grep '^version=' "$MODULE_PROP" 2>/dev/null | cut -d= -f2-)

ROOT_MANAGER="unknown"
[ -d /data/adb/ksu ] && ROOT_MANAGER="kernelsu"
[ -d /data/adb/ap ] && ROOT_MANAGER="apatch"
[ -d /data/adb/magisk ] && ROOT_MANAGER="magisk"

KPROBES="unknown"
KRETPROBES="unknown"
if [ -r /proc/config.gz ]; then
    CFG=$(zcat /proc/config.gz 2>/dev/null || gunzip -c /proc/config.gz 2>/dev/null)
    if [ -n "$CFG" ]; then
        KPROBES=$(printf '%s\n' "$CFG" | awk -F= '/^CONFIG_KPROBES=/{print $2; exit}')
        KRETPROBES=$(printf '%s\n' "$CFG" | awk -F= '/^CONFIG_KRETPROBES=/{print $2; exit}')
        [ -z "$KPROBES" ] && KPROBES="n"
        [ -z "$KRETPROBES" ] && KRETPROBES="n"
    fi
fi

if [ ! -f "$KO" ]; then
    INSMOD_EXIT=127
    INSMOD_STDERR="vpnhide_kmod.ko not found at $KO"
else
    INSMOD_STDERR=$(insmod "$KO" 2>&1 >/dev/null)
    INSMOD_EXIT=$?
fi

if grep -q vpnhide_kmod /proc/modules 2>/dev/null; then
    LOADED=1
else
    LOADED=0
fi

# Separate file for dmesg excerpt — may span many lines.
dmesg 2>/dev/null | grep -iE 'vpnhide|kretprobe|modules.verify|version magic' | tail -n 40 > "$DMESG_FILE" 2>/dev/null
chmod 0644 "$DMESG_FILE" 2>/dev/null

{
    printf 'timestamp=%s\n' "$NOW"
    printf 'boot_id=%s\n' "$BOOT_ID"
    printf 'uname_r=%s\n' "$UNAME_R"
    printf 'gki_variant=%s\n' "$GKI_VARIANT"
    printf 'kmod_version=%s\n' "$KMOD_VERSION"
    printf 'root_manager=%s\n' "$ROOT_MANAGER"
    printf 'kprobes=%s\n' "$KPROBES"
    printf 'kretprobes=%s\n' "$KRETPROBES"
    printf 'insmod_exit=%s\n' "$INSMOD_EXIT"
    printf 'loaded=%s\n' "$LOADED"
    printf 'insmod_stderr=%s\n' "$(sanitize "$INSMOD_STDERR")"
} > "$STATUS_FILE.tmp" && mv "$STATUS_FILE.tmp" "$STATUS_FILE"
chmod 0644 "$STATUS_FILE" 2>/dev/null

if [ "$LOADED" = "1" ]; then
    log -t vpnhide "kernel module loaded (gki=$GKI_VARIANT kernel=$UNAME_R)"
    exit 0
else
    log -t vpnhide "kernel module NOT loaded (exit=$INSMOD_EXIT gki=$GKI_VARIANT kernel=$UNAME_R): $INSMOD_STDERR"
    exit 1
fi
