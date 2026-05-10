#!/system/bin/sh
# Reads /data/adb/vpnhide_ports/observers.txt (one package name per line)
# and applies port-blocking targets to the kernel module.
#
# Used for hiding locally-bound VPN/proxy daemons from apps that probe
# via connect(127.0.0.1, PORT).
#
# Callable from service.sh at boot and from the VPNHide Next app via su.

OBSERVERS_FILE="/data/adb/vpnhide_ports/observers.txt"
CHAIN4="vpnhide_out"
CHAIN6="vpnhide_out6"

# Find vpnhide-ctl binary
CTL="/data/adb/modules/vpnhide_kmod/vpnhide-ctl"
[ -f "$CTL" ] || CTL="/data/adb/modules/vpnhide/vpnhide-ctl" # Fallback
[ -f "$CTL" ] || CTL="vpnhide-ctl" # Fallback to PATH

# Wait for PackageManager so pm list packages -U works. Relevant at boot.
pm_ready=0
for i in $(seq 1 30); do
    if pm list packages >/dev/null 2>&1; then
        pm_ready=1
        break
    fi
    sleep 1
done

if [ "$pm_ready" != 1 ]; then
    log -t vpnhide_ports "pm never became ready after 30s; skipping apply"
    exit 1
fi

# resolve_uids <targets_file> — prints a space-separated list of UIDs to stdout.
# Borrowed from service.sh for consistency.
resolve_uids() {
    local targets_file="$1"
    [ -f "$targets_file" ] || return
    
    local all_pkgs
    all_pkgs="$(pm list packages -U --user all 2>/dev/null)"
    [ -n "$all_pkgs" ] || return

    local uids=""
    while IFS= read -r line || [ -n "$line" ]; do
        local pkg
        pkg="$(echo "$line" | tr -d '[:space:]')"
        [ -z "$pkg" ] && continue
        case "$pkg" in \#*) continue ;; esac
        
        local pkg_esc
        pkg_esc="$(echo "$pkg" | sed 's/\./\\./g')"
        
        local uid_csv
        uid_csv="$(echo "$all_pkgs" | awk -v p="^package:${pkg_esc}[ :]" '$0 ~ p { sub(/.*uid:/, "", $0); print $0 }')"
        
        if [ -n "$uid_csv" ]; then
            local expanded
            expanded="$(echo "$uid_csv" | tr ',\n' '  ')"
            uids="$uids $expanded"
        fi
    done < "$targets_file"
    
    [ -n "$uids" ] && echo "$uids" | tr ' ' '\n' | grep -v '^$' | sort -u | xargs
}

RULES_FILE="/data/adb/vpnhide_ports/rules.txt"

UIDS="$(resolve_uids "$OBSERVERS_FILE")"

# Apply UIDs and rules to kernel module via IOCTL
if [ -n "$UIDS" ]; then
    ARGS=""
    for U in $UIDS; do
        # Find package name for this UID to lookup rules
        # We assume uidToPkg mapping is available or we re-resolve it
        # Since observers.txt has package names, it's easier to iterate over packages.
        :
    done
    
    # Better approach: Iterate over observers.txt directly
    ARGS=""
    ALL_PKGS="$(pm list packages -U --user all 2>/dev/null)"
    
    while IFS= read -r line || [ -n "$line" ]; do
        pkg="$(echo "$line" | tr -d '[:space:]')"
        [ -z "$pkg" ] && continue
        case "$pkg" in \#*) continue ;; esac
        
        # Resolve UIDs for this package
        pkg_esc="$(echo "$pkg" | sed 's/\./\\./g')"
        u_csv="$(echo "$ALL_PKGS" | awk -v p="^package:${pkg_esc}[ :]" '$0 ~ p { sub(/.*uid:/, "", $0); print $0 }')"
        [ -z "$u_csv" ] && continue
        
        u_list="$(echo "$u_csv" | tr ',\n' '  ')"
        
        # Check if granular rules exist for this package
        # Format in rules.txt: <pkg> <start>-<end>:<proto> ...
        rules_line="$(grep "^$pkg " "$RULES_FILE" | head -n1)"
        
        for val in $u_list; do
            if [ -n "$rules_line" ]; then
                # Granular rules found
                rule_count=0
                r_args=""
                # Skip the package name
                for r in $rules_line; do
                    [ "$r" = "$pkg" ] && continue
                    # r is <start>-<end>:<proto>
                    s_e="${r%:*}"
                    p="${r#*:}"
                    s="${s_e%-*}"
                    e="${s_e#*-}"
                    r_args="$r_args $s $e $p"
                    rule_count=$((rule_count + 1))
                done
                ARGS="$ARGS $val $rule_count$r_args"
            else
                # No rules, default to block all
                ARGS="$ARGS $val 1 0 65535 2"
            fi
        done
    done < "$OBSERVERS_FILE"

    if [ -n "$ARGS" ]; then
        # shellcheck disable=SC2086
        $CTL port_rules $ARGS
        rc=$?
    else
        $CTL port_rules
        rc=$?
    fi
else
    # Clear targets
    $CTL port_rules
    rc=$?
fi

# Cleanup old iptables rules if they exist (migration from standalone portshide)
while iptables -D OUTPUT -j "$CHAIN4" >/dev/null 2>&1; do :; done
iptables -F "$CHAIN4" >/dev/null 2>&1
iptables -X "$CHAIN4" >/dev/null 2>&1

while ip6tables -D OUTPUT -j "$CHAIN6" >/dev/null 2>&1; do :; done
ip6tables -F "$CHAIN6" >/dev/null 2>&1
ip6tables -X "$CHAIN6" >/dev/null 2>&1

count="$(echo "$UIDS" | wc -w)"
log -t vpnhide_ports "applied port-hiding to kernel: $count observer(s), rc=$rc"

[ "$rc" = 0 ]
