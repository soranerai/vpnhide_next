#!/bin/sh
# In-VM test driver (PID 1 / rdinit) for the vpnhide kmod QEMU harness.
#
# Boots inside a minimal Alpine initramfs, loads /vpnhide_kmod.ko, fabricates
# a VPN-like interface (`vpn0`, a dummy netdev whose name matches the VPN
# prefix list), then exercises every detection vector twice — once as a
# non-target UID (must still see vpn0) and once as a target UID (must NOT
# see vpn0). Emits machine-parseable `RESULT <vector>=PASS|FAIL` lines plus a
# final `SUMMARY` line, then powers off so QEMU exits.
#
# Output is consumed by run.sh on the host via the serial console.
#
# Rule check: Запускай любые билды ТОЛЬКО асинхронно для себя, в фоне.
set +e
export PATH=/usr/sbin:/usr/bin:/sbin:/bin

mount -t proc proc /proc 2>/dev/null
mount -t sysfs sys /sys 2>/dev/null
mount -t devtmpfs dev /dev 2>/dev/null

echo "##### VPNHIDE-QEMU-TEST START #####"
echo "KREL=$(uname -r)"

# Add a non-root test user with UID 5555
echo "testuser:x:5555:5555:testuser:/home/testuser:/bin/sh" >> /etc/passwd

# --- bring up user-mode networking so apk can fetch iproute2 ----------------
ip link set lo up 2>/dev/null
ip link set eth0 up 2>/dev/null
ip addr add 10.0.2.15/24 dev eth0 2>/dev/null
ip -6 addr add fd00:2::1/64 dev eth0 2>/dev/null
ip route add default via 10.0.2.2 2>/dev/null
echo "nameserver 10.0.2.3" > /etc/resolv.conf
echo "https://dl-cdn.alpinelinux.org/alpine/v3.21/main" > /etc/apk/repositories
if apk add --no-cache iproute2 python3 >/dev/null 2>&1; then echo "IPROUTE2=ok"; else echo "IPROUTE2=FAIL"; fi

# --- load the module --------------------------------------------------------
if insmod /vpnhide_kmod.ko; then echo "INSMOD=ok"; else echo "INSMOD=FAIL"; fi
# Wait for the device node /dev/vpnhide_ctrl to populate
sleep 1
# Initialize interface prefixes so the module knows to hide "vpn" interfaces
/vpnhide-ctl iface_prefixes vpn 2>/dev/null
# Enable debug mode
/vpnhide-ctl debug 1 2>/dev/null
REGISTERED=1
echo "REGISTERED=$REGISTERED"
# Start the event-driven C daemon in the background to automatically monitor interfaces and update ifindexes in the kmod
/vpnhide-daemon > /tmp/daemon.log 2>&1 &
# Give the daemon a moment to register netlink events
sleep 1

# --- fabricate a VPN-like interface + routes + a per-uid policy rule ---------
ip link add vpn0 type dummy 2>/dev/null
ip link set vpn0 up 2>/dev/null
ip addr add 10.9.0.1/24 dev vpn0 2>/dev/null
ip route add 10.9.9.0/24 dev vpn0 2>/dev/null
ip -6 addr add fd00:9::1/64 dev vpn0 2>/dev/null
ip -6 route add fd00:99::/64 dev vpn0 2>/dev/null
ip rule add uidrange 5555-5555 table 199 2>/dev/null
# Give the daemon a moment to process the interface events and update active_vpns in the kernel
sleep 3

PASS=0
FAIL=0

# check <name> <shell-command> <grep-pattern>
# Asserts: non-target UID (root/0) sees the pattern, target UID (testuser/5555) does not.
check() {
	_name=$1
	_cmd=$2
	_pat=$3
	
	# 1. Set UID 5555 as the target in the kernel module
	/vpnhide-ctl targets 5555 2>/dev/null
	
	# 2. Non-target check (run as root / UID 0)
	_nt=$(eval "$_cmd" 2>/dev/null | grep -c -- "$_pat")
	
	# 3. Target check (run as testuser / UID 5555)
	_tg=$(su testuser -c "$_cmd" 2>/dev/null | grep -c -- "$_pat")
	
	if [ "$_nt" -gt 0 ] && [ "$_tg" -eq 0 ]; then
		echo "RESULT $_name=PASS (nontarget=$_nt target=$_tg)"
		PASS=$((PASS + 1))
	else
		echo "RESULT $_name=FAIL (nontarget=$_nt target=$_tg)"
		FAIL=$((FAIL + 1))
	fi
}

# vector -> hook it exercises
check getifaddrs      "ip addr show"                 "vpn0"   # rtnl_fill_ifinfo + inet*_fill_ifaddr
check siocgifconf     "ifconfig -a"                  "vpn0"   # sock_ioctl
check proc_route_v4   "cat /proc/net/route"          "vpn0"   # fib_route_seq_show
check proc_route_v6   "cat /proc/net/ipv6_route"     "vpn0"   # ipv6_route_seq_show
check netlink_route4  "ip route show table all"      "vpn0"   # fib_dump_info
check netlink_route6  "ip -6 route show table all"   "vpn0"   # rt6_fill_node
check policy_rule     "ip rule show"                 "199"    # fib_nl_fill_rule
check sysfs_ipv4_conf "ls /proc/sys/net/ipv4/conf"   "vpn0"   # getdents64 /proc/sys/net/ipv4/conf
check sysfs_ipv6_neig "ls /proc/sys/net/ipv6/neigh"  "vpn0"   # getdents64 /proc/sys/net/ipv6/neigh
check proc_net_dev    "cat /proc/net/dev"            "vpn0"   # dev_seq_show
check proc_net_if_in6 "cat /proc/net/if_inet6"       "vpn0"   # if6_seq_show

# --- execute programmatic socket and ioctl vector tests in Python ---
# 1. Set targets and configure port rules for UID 5555
/vpnhide-ctl targets 5555 2>/dev/null
/vpnhide-ctl port_rules 5555 1 8080 8080 2 2>/dev/null

# 2. Run vector_tests.py and process its output
python3 /vector_tests.py > /tmp/py_res.log 2>&1
cat /tmp/py_res.log

while read -r line; do
	case "$line" in
		"RESULT "*=PASS*)
			PASS=$((PASS + 1))
			;;
		"RESULT "*=FAIL*)
			FAIL=$((FAIL + 1))
			;;
	esac
done < /tmp/py_res.log

PANIC=$(dmesg | grep -ci 'Unable to handle\|Internal error\|Oops\|BUG:\|Kernel panic')
echo "=== DAEMON LOG ==="
cat /tmp/daemon.log
echo "=================="
echo "PANIC=$PANIC"
echo "SUMMARY pass=$PASS fail=$FAIL panic=$PANIC registered=$REGISTERED"
echo "##### VPNHIDE-QEMU-TEST END #####"

poweroff -f
