#!/usr/bin/env python3
import ctypes
import fcntl
import os
import platform
import socket
import struct
import sys

# ruff: noqa: E501

# Constants
SO_BINDTODEVICE = getattr(socket, "SO_BINDTODEVICE", 25)
SO_BINDTOIFINDEX = 62  # On Linux (asm-generic/socket.h), SO_BINDTOIFINDEX is 62
SIOCGIFFLAGS = 0x8913


def safe_fork():
    """Flush buffers before forking to prevent duplicate log outputs in child processes."""
    sys.stdout.flush()
    sys.stderr.flush()
    return os.fork()


def test_dev_ioctl(vpn0_idx):
    print("\n--- dev_ioctl checks ---")

    # 1. Non-target check (root)
    try:
        idx = socket.if_nametoindex("vpn0")
        print(
            f"[dev_ioctl] Non-target if_nametoindex('vpn0') returned: {idx} (expected: {vpn0_idx})"
        )
        assert idx == vpn0_idx, f"Expected index {vpn0_idx}, got {idx}"
    except Exception as e:
        print(f"FAIL: dev_ioctl if_nametoindex non-target: {e}")
        return False

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        ifr = struct.pack("16sH", b"vpn0", 0)
        fcntl.ioctl(s.fileno(), SIOCGIFFLAGS, ifr)
        print("[dev_ioctl] Non-target ioctl(SIOCGIFFLAGS, 'vpn0') succeeded as expected")
    except Exception as e:
        print(f"FAIL: dev_ioctl SIOCGIFFLAGS non-target: {e}")
        return False

    # 2. Target check (UID 5555)
    pid = safe_fork()
    if pid == 0:
        # Child process
        try:
            os.setuid(5555)
            # Should fail (raises OSError/ValueError)
            try:
                socket.if_nametoindex("vpn0")
                print("FAIL: dev_ioctl if_nametoindex target succeeded but should have failed")
                sys.exit(1)
            except (OSError, ValueError) as e:
                print(f"[dev_ioctl] Target if_nametoindex('vpn0') failed as expected: {e}")

            try:
                ifr = struct.pack("16sH", b"vpn0", 0)
                fcntl.ioctl(s.fileno(), SIOCGIFFLAGS, ifr)
                print("FAIL: dev_ioctl SIOCGIFFLAGS target succeeded but should have failed")
                sys.exit(1)
            except OSError as e:
                print(
                    f"[dev_ioctl] Target ioctl(SIOCGIFFLAGS, 'vpn0') failed as expected: errno {e.errno} ({e.strerror})"
                )
                if e.errno != 19:  # ENODEV
                    print(f"FAIL: dev_ioctl SIOCGIFFLAGS target expected errno 19, got {e.errno}")
                    sys.exit(1)

            sys.exit(0)
        except Exception as e:
            print(f"FAIL: child process exception: {e}")
            sys.exit(1)
    else:
        # Parent
        wpid, status = os.waitpid(pid, 0)
        if status != 0:
            return False
    return True


def test_setsockopt(vpn0_idx):
    print("\n--- setsockopt checks ---")

    # 1. Non-target checks (root)
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, SO_BINDTODEVICE, b"vpn0")
        print("[setsockopt] Non-target setsockopt(SO_BINDTODEVICE, 'vpn0') succeeded as expected")
    except Exception as e:
        print(f"FAIL: setsockopt SO_BINDTODEVICE non-target: {e}")
        return False

    s2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s2.setsockopt(socket.SOL_SOCKET, SO_BINDTOIFINDEX, struct.pack("i", vpn0_idx))
        print(
            f"[setsockopt] Non-target setsockopt(SO_BINDTOIFINDEX, {vpn0_idx}) succeeded as expected"
        )
    except Exception as e:
        print(f"FAIL: setsockopt SO_BINDTOIFINDEX non-target: {e}")
        return False

    # 2. Target check (UID 5555)
    pid = safe_fork()
    if pid == 0:
        try:
            os.setuid(5555)
            s_tgt = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            try:
                s_tgt.setsockopt(socket.SOL_SOCKET, SO_BINDTODEVICE, b"vpn0")
                print("FAIL: setsockopt SO_BINDTODEVICE target succeeded but should have failed")
                sys.exit(1)
            except OSError as e:
                print(
                    f"[setsockopt] Target setsockopt(SO_BINDTODEVICE, 'vpn0') failed as expected: errno {e.errno} ({e.strerror})"
                )
                if e.errno != 19:
                    print(
                        f"FAIL: setsockopt SO_BINDTODEVICE target expected errno 19, got {e.errno}"
                    )
                    sys.exit(1)

            try:
                s_tgt.setsockopt(socket.SOL_SOCKET, SO_BINDTOIFINDEX, struct.pack("i", vpn0_idx))
                print("FAIL: setsockopt SO_BINDTOIFINDEX target succeeded but should have failed")
                sys.exit(1)
            except OSError as e:
                print(
                    f"[setsockopt] Target setsockopt(SO_BINDTOIFINDEX, {vpn0_idx}) failed as expected: errno {e.errno} ({e.strerror})"
                )
                if e.errno != 19:
                    print(
                        f"FAIL: setsockopt SO_BINDTOIFINDEX target expected errno 19, got {e.errno}"
                    )
                    sys.exit(1)

            sys.exit(0)
        except Exception as e:
            print(f"FAIL: child exception: {e}")
            sys.exit(1)
    else:
        wpid, status = os.waitpid(pid, 0)
        if status != 0:
            return False
    return True


def test_getsockopt(vpn0_idx):
    print("\n--- getsockopt checks ---")

    # Setup bound sockets as root
    s_dev = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s_dev.setsockopt(socket.SOL_SOCKET, SO_BINDTODEVICE, b"vpn0\x00")

    s_idx = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s_idx.setsockopt(socket.SOL_SOCKET, SO_BINDTOIFINDEX, struct.pack("i", vpn0_idx))

    # Drop privileges to target UID (5555)
    pid = safe_fork()
    if pid == 0:
        try:
            os.setuid(5555)
            # 1. getsockopt SO_BINDTODEVICE
            val = s_dev.getsockopt(socket.SOL_SOCKET, SO_BINDTODEVICE, 256)
            clean_val = val.strip(b"\x00")
            print(
                f"[getsockopt] Target getsockopt(SO_BINDTODEVICE) returned: {clean_val!r} (expected: empty)"
            )
            if clean_val != b"":
                print(
                    f"FAIL: getsockopt SO_BINDTODEVICE target: expected empty string, got {clean_val}"
                )
                sys.exit(1)

            # 2. getsockopt SO_BINDTOIFINDEX
            val_idx = s_idx.getsockopt(socket.SOL_SOCKET, SO_BINDTOIFINDEX, 4)
            idx = struct.unpack("i", val_idx)[0]
            print(f"[getsockopt] Target getsockopt(SO_BINDTOIFINDEX) returned: {idx} (expected: 0)")
            if idx != 0:
                print(f"FAIL: getsockopt SO_BINDTOIFINDEX target: expected 0, got {idx}")
                sys.exit(1)

            sys.exit(0)
        except Exception as e:
            print(f"FAIL: child exception: {e}")
            sys.exit(1)
    else:
        wpid, status = os.waitpid(pid, 0)
        if status != 0:
            return False
    return True


def test_getsockname():
    print("\n--- getsockname spoofing checks ---")

    # Bind IPv4 UDP socket to 10.9.0.1
    s_v4 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s_v4.bind(("10.9.0.1", 0))
        ip4_nt, port4_nt = s_v4.getsockname()
        print(
            f"[getsockname] Non-target getsockname IPv4: {ip4_nt}:{port4_nt} (expected: 10.9.0.1)"
        )
    except Exception as e:
        print(f"FAIL: getsockname IPv4 bind: {e}")
        return False

    # Bind IPv6 UDP socket to fd00:9::1
    s_v6 = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
    try:
        s_v6.bind(("fd00:9::1", 0))
        ip6_nt, port6_nt, _, _ = s_v6.getsockname()
        print(
            f"[getsockname] Non-target getsockname IPv6: [{ip6_nt}]:{port6_nt} (expected: fd00:9::1)"
        )
    except Exception as e:
        print(f"FAIL: getsockname IPv6 bind: {e}")
        return False

    # Drop privileges to target UID (5555)
    pid = safe_fork()
    if pid == 0:
        try:
            os.setuid(5555)
            # IPv4 getsockname
            ip4, port4 = s_v4.getsockname()
            print(
                f"[getsockname] Target getsockname IPv4: {ip4}:{port4} (expected: spoofed/shielded from 10.9.0.1)"
            )
            if ip4 == "10.9.0.1":
                print(f"FAIL: getsockname IPv4 target: got unshielded VPN IP '{ip4}'")
                sys.exit(1)

            # IPv6 getsockname
            ip6, port6, flow, scope = s_v6.getsockname()
            print(
                f"[getsockname] Target getsockname IPv6: [{ip6}]:{port6} (expected: spoofed/shielded from fd00:9::1)"
            )
            if ip6 == "fd00:9::1":
                print(f"FAIL: getsockname IPv6 target: got unshielded VPN IP '{ip6}'")
                sys.exit(1)

            sys.exit(0)
        except Exception as e:
            print(f"FAIL: child exception: {e}")
            sys.exit(1)
    else:
        wpid, status = os.waitpid(pid, 0)
        if status != 0:
            return False
    return True


def test_connect_port_block():
    print("\n--- connect port block checks ---")

    # Start a TCP listener on loopback port 8080 (as root)
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        listener.bind(("127.0.0.1", 8080))
        listener.listen(1)
    except Exception as e:
        print(f"FAIL: connect port block listener bind/listen: {e}")
        return False

    # First verify that non-target (root) can connect successfully
    s_nt = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s_nt.settimeout(2.0)
    try:
        s_nt.connect(("127.0.0.1", 8080))
        s_nt.close()
        print(
            "[connect_port_block] Non-target connected to 127.0.0.1:8080 successfully as expected"
        )
    except Exception as e:
        print(f"FAIL: connect port block non-target connection failed: {e}")
        listener.close()
        return False

    # Drop privileges and verify target is blocked (receives ECONNREFUSED)
    pid = safe_fork()
    if pid == 0:
        try:
            os.setuid(5555)
            s_tgt = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s_tgt.settimeout(2.0)
            try:
                s_tgt.connect(("127.0.0.1", 8080))
                print("FAIL: connect port block target succeeded but should have failed")
                sys.exit(1)
            except OSError as e:
                print(
                    f"[connect_port_block] Target connection to 127.0.0.1:8080 failed as expected: errno {e.errno} ({e.strerror})"
                )
                if e.errno != 111:  # ECONNREFUSED is 111
                    print(f"FAIL: connect port block target expected errno 111, got {e.errno}")
                    sys.exit(1)
            sys.exit(0)
        except Exception as e:
            print(f"FAIL: child exception: {e}")
            sys.exit(1)
    else:
        wpid, status = os.waitpid(pid, 0)
        listener.close()
        if status != 0:
            return False
    return True


def test_bind_port_block():
    print("\n--- bind port block checks ---")

    # Drop privileges and verify that binding to port 8080 redirects to port 0 (ephemeral port)
    pid = safe_fork()
    if pid == 0:
        try:
            os.setuid(5555)
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                s.bind(("127.0.0.1", 8080))
                ip, port = s.getsockname()
                print(
                    f"[bind_port_block] Target bound to 127.0.0.1:8080. Redirected getsockname port: {port}"
                )
                if port == 8080:
                    print(
                        "FAIL: bind port block target bound to 8080, expected redirection to ephemeral port"
                    )
                    sys.exit(1)
                if port == 0:
                    print("FAIL: bind port block target getsockname returned 0")
                    sys.exit(1)
            except Exception as e:
                print(f"FAIL: bind port block target bind error: {e}")
                sys.exit(1)
            sys.exit(0)
        except Exception as e:
            print(f"FAIL: child exception: {e}")
            sys.exit(1)
    else:
        wpid, status = os.waitpid(pid, 0)
        if status != 0:
            return False
    return True


# BPF Constants and Structs for Laundering checks

machine = platform.machine()
if machine in ("aarch64", "arm64"):
    __NR_bpf = 280
elif machine in ("x86_64", "amd64"):
    __NR_bpf = 321
else:
    __NR_bpf = 280

BPF_MAP_CREATE = 0
BPF_MAP_LOOKUP_ELEM = 1
BPF_MAP_UPDATE_ELEM = 2
BPF_MAP_TYPE_HASH = 2


class VhStatsValue(ctypes.Structure):
    _fields_ = [
        ("rxBytes", ctypes.c_uint64),
        ("rxPackets", ctypes.c_uint64),
        ("txBytes", ctypes.c_uint64),
        ("txPackets", ctypes.c_uint64),
    ]


class BpfAttrCreate(ctypes.Structure):
    _fields_ = [
        ("map_type", ctypes.c_uint32),
        ("key_size", ctypes.c_uint32),
        ("value_size", ctypes.c_uint32),
        ("max_entries", ctypes.c_uint32),
        ("map_flags", ctypes.c_uint32),
        ("inner_map_fd", ctypes.c_uint32),
        ("numa_node", ctypes.c_uint32),
        ("map_name", ctypes.c_char * 16),
        ("map_ifindex", ctypes.c_uint32),
        ("btf_fd", ctypes.c_uint32),
        ("btf_key_type_id", ctypes.c_uint32),
        ("btf_value_type_id", ctypes.c_uint32),
        ("btf_vmlinux_value_type_id", ctypes.c_uint32),
        ("map_extra", ctypes.c_uint64),
    ]


class BpfAttrElem(ctypes.Structure):
    _fields_ = [
        ("map_fd", ctypes.c_uint32),
        ("key", ctypes.c_uint64),
        ("value", ctypes.c_uint64),
        ("flags", ctypes.c_uint64),
    ]


class BpfAttrBatch(ctypes.Structure):
    _fields_ = [
        ("in_batch", ctypes.c_uint64),
        ("out_batch", ctypes.c_uint64),
        ("keys", ctypes.c_uint64),
        ("values", ctypes.c_uint64),
        ("count", ctypes.c_uint32),
        ("map_fd", ctypes.c_uint32),
        ("elem_flags", ctypes.c_uint64),
        ("flags", ctypes.c_uint64),
    ]


class BpfAttr(ctypes.Union):
    _fields_ = [
        ("raw", ctypes.c_ubyte * 128),
        ("create", BpfAttrCreate),
        ("elem", BpfAttrElem),
        ("batch", BpfAttrBatch),
    ]


libc = ctypes.CDLL(None, use_errno=True)


def bpf_syscall(cmd, attr, size):
    ret = libc.syscall(__NR_bpf, cmd, ctypes.byref(attr), size)
    if ret < 0:
        err = ctypes.get_errno()
        raise OSError(err, os.strerror(err))
    return ret


def create_stats_map():
    attr = BpfAttr()
    attr.create.map_type = BPF_MAP_TYPE_HASH
    attr.create.key_size = 4
    attr.create.value_size = 32
    attr.create.max_entries = 1000
    attr.create.map_name = b"iface_stats"
    return bpf_syscall(BPF_MAP_CREATE, attr, ctypes.sizeof(attr))


def update_map_elem(map_fd, ifindex, rx, tx):
    key = ctypes.c_uint32(ifindex)
    val = VhStatsValue(
        rxBytes=rx,
        rxPackets=rx // 100 if rx >= 100 else 1,
        txBytes=tx,
        txPackets=tx // 100 if tx >= 100 else 1,
    )
    attr = BpfAttr()
    attr.elem.map_fd = map_fd
    attr.elem.key = ctypes.addressof(key)
    attr.elem.value = ctypes.addressof(val)
    bpf_syscall(BPF_MAP_UPDATE_ELEM, attr, ctypes.sizeof(attr))


def lookup_map_elem(map_fd, ifindex):
    key = ctypes.c_uint32(ifindex)
    val = VhStatsValue()
    attr = BpfAttr()
    attr.elem.map_fd = map_fd
    attr.elem.key = ctypes.addressof(key)
    attr.elem.value = ctypes.addressof(val)
    bpf_syscall(BPF_MAP_LOOKUP_ELEM, attr, ctypes.sizeof(attr))
    return val


def test_bpf_laundering(vpn0_idx):
    print("\n--- BPF map laundering checks ---")

    try:
        eth0_idx = socket.if_nametoindex("eth0")
    except Exception as e:
        print(f"FAIL: BPF test cannot find eth0: {e}")
        return False

    # 1. Create BPF map as root
    try:
        map_fd = create_stats_map()
        print(f"[BPF] Created map 'iface_stats' with fd: {map_fd}")
    except Exception as e:
        print(f"FAIL: BPF map creation: {e}")
        return False

    # 2. Populate stats
    try:
        update_map_elem(map_fd, vpn0_idx, 1000, 2000)
        update_map_elem(map_fd, eth0_idx, 5000, 6000)
    except Exception as e:
        print(f"FAIL: BPF map update: {e}")
        return False

    # 3. Check under Non-target (root): laundering should occur
    try:
        # Check VPN: statistics must be hidden (zeroes)
        vpn_val = lookup_map_elem(map_fd, vpn0_idx)
        print(f"[BPF Non-Target] vpn0: rx={vpn_val.rxBytes}, tx={vpn_val.txBytes}")
        assert vpn_val.rxBytes == 0 and vpn_val.txBytes == 0, "VPN stats not zeroed!"

        # Check cover: statistics must absorb VPN traffic
        eth_val = lookup_map_elem(map_fd, eth0_idx)
        print(f"[BPF Non-Target] eth0: rx={eth_val.rxBytes}, tx={eth_val.txBytes}")
        assert eth_val.rxBytes == 6000 and eth_val.txBytes == 8000, (
            "Cover interface stats not laundered!"
        )
        print("[BPF Non-Target] Single lookup checks passed")
    except Exception as e:
        print(f"FAIL: BPF non-target lookup verification failed: {e}")
        return False

    # 4. Check under Target UID (5555): hook should be bypassed (raw stats visible)
    pid = safe_fork()
    if pid == 0:
        try:
            os.setuid(5555)
            vpn_val = lookup_map_elem(map_fd, vpn0_idx)
            eth_val = lookup_map_elem(map_fd, eth0_idx)
            print(f"[BPF Target] vpn0: rx={vpn_val.rxBytes}, tx={vpn_val.txBytes}")
            print(f"[BPF Target] eth0: rx={eth_val.rxBytes}, tx={eth_val.txBytes}")

            if vpn_val.rxBytes != 1000 or eth_val.rxBytes != 5000:
                print("FAIL: BPF target check expected raw values, got spoofed values")
                sys.exit(1)

            print("[BPF Target] Target bypass checks passed")
            sys.exit(0)
        except Exception as e:
            print(f"FAIL: BPF child exception: {e}")
            sys.exit(1)
    else:
        wpid, status = os.waitpid(pid, 0)
        if status != 0:
            return False

    return True


def test_proc_sys_net():
    print("\n--- proc / sysfs net checks ---")

    # 1. Non-target check (root)
    try:
        ipv4_conf = os.listdir("/proc/sys/net/ipv4/conf")
        ipv6_neigh = os.listdir("/proc/sys/net/ipv6/neigh")
        with open("/proc/net/dev") as f:
            proc_net_dev = f.read()
        with open("/proc/net/if_inet6") as f:
            proc_net_if_inet6 = f.read()

        assert "vpn0" in ipv4_conf, "vpn0 not in /proc/sys/net/ipv4/conf under root"
        assert "vpn0" in ipv6_neigh, "vpn0 not in /proc/sys/net/ipv6/neigh under root"
        assert "vpn0" in proc_net_dev, "vpn0 not in /proc/net/dev under root"
        assert "vpn0" in proc_net_if_inet6, "vpn0 not in /proc/net/if_inet6 under root"
        print("[proc/sysfs net] Non-target checks passed")
    except Exception as e:
        print(f"FAIL: proc/sysfs net non-target check failed: {e}")
        return False

    # 2. Target check (UID 5555)
    pid = safe_fork()
    if pid == 0:
        try:
            os.setuid(5555)
            ipv4_conf = os.listdir("/proc/sys/net/ipv4/conf")
            ipv6_neigh = os.listdir("/proc/sys/net/ipv6/neigh")
            with open("/proc/net/dev") as f:
                proc_net_dev = f.read()
            with open("/proc/net/if_inet6") as f:
                proc_net_if_inet6 = f.read()

            if "vpn0" in ipv4_conf:
                print("FAIL: vpn0 visible in /proc/sys/net/ipv4/conf for target UID")
                sys.exit(1)
            if "vpn0" in ipv6_neigh:
                print("FAIL: vpn0 visible in /proc/sys/net/ipv6/neigh for target UID")
                sys.exit(1)
            if "vpn0" in proc_net_dev:
                print("FAIL: vpn0 visible in /proc/net/dev for target UID")
                sys.exit(1)
            if "vpn0" in proc_net_if_inet6:
                print("FAIL: vpn0 visible in /proc/net/if_inet6 for target UID")
                sys.exit(1)

            print("[proc/sysfs net] Target checks passed")
            sys.exit(0)
        except Exception as e:
            print(f"FAIL: proc/sysfs net child exception: {e}")
            sys.exit(1)
    else:
        wpid, status = os.waitpid(pid, 0)
        if status != 0:
            return False

    return True


def test_udp_queue_pressure():
    print("\n--- UDP queue pressure checks ---")

    # 1. Non-target check (root)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setblocking(False)
    success_count_nt = 0
    for _ in range(1000):
        try:
            s.sendto(b"test_payload_32_bytes_long_here", ("127.0.0.1", 12345))
            success_count_nt += 1
        except OSError:
            pass
    print(f"[UDP Queue Pressure] Non-target success rate: {success_count_nt}/1000")
    if success_count_nt < 950:
        print(f"FAIL: UDP queue pressure non-target success rate too low: {success_count_nt}/1000")
        return False

    # 2. Target check (UID 5555)
    pid = safe_fork()
    if pid == 0:
        try:
            os.setuid(5555)
            s_tgt = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s_tgt.setblocking(False)
            success_count = 0
            eagain_count = 0
            for _ in range(1000):
                try:
                    s_tgt.sendto(b"test_payload_32_bytes_long_here", ("127.0.0.1", 12345))
                    success_count += 1
                except OSError as e:
                    if e.errno == 11:  # EAGAIN is 11
                        eagain_count += 1
            print(
                f"[UDP Queue Pressure] Target success rate: {success_count}/1000, EAGAIN: {eagain_count}/1000"
            )
            if success_count > 600:
                print(
                    f"FAIL: UDP queue pressure target succeeded too many times: {success_count}/1000 (should be <= 600)"
                )
                sys.exit(1)
            if eagain_count == 0:
                print("FAIL: UDP queue pressure target did not receive any EAGAIN errors")
                sys.exit(1)
            sys.exit(0)
        except Exception as e:
            print(f"FAIL: child exception: {e}")
            sys.exit(1)
    else:
        wpid, status = os.waitpid(pid, 0)
        if status != 0:
            return False
    return True


def main():
    try:
        vpn0_idx = socket.if_nametoindex("vpn0")
    except Exception as e:
        print(f"FAIL: cannot find interface vpn0: {e}")
        sys.exit(1)

    success = True

    # Run dev_ioctl
    if not test_dev_ioctl(vpn0_idx):
        print("RESULT dev_ioctl=FAIL")
        success = False
    else:
        print("RESULT dev_ioctl=PASS")

    # Run setsockopt
    if not test_setsockopt(vpn0_idx):
        print("RESULT setsockopt=FAIL")
        success = False
    else:
        print("RESULT setsockopt=PASS")

    # Run getsockopt
    if not test_getsockopt(vpn0_idx):
        print("RESULT getsockopt=FAIL")
        success = False
    else:
        print("RESULT getsockopt=PASS")

    # Run getsockname
    if not test_getsockname():
        print("RESULT getsockname=FAIL")
        success = False
    else:
        print("RESULT getsockname=PASS")

    # Run connect port block
    if not test_connect_port_block():
        print("RESULT connect_port_block=FAIL")
        success = False
    else:
        print("RESULT connect_port_block=PASS")

    # Run bind port block
    if not test_bind_port_block():
        print("RESULT bind_port_block=FAIL")
        success = False
    else:
        print("RESULT bind_port_block=PASS")

    # Run BPF laundering checks
    if not test_bpf_laundering(vpn0_idx):
        print("RESULT bpf_laundering=FAIL")
        success = False
    else:
        print("RESULT bpf_laundering=PASS")

    # Run proc/sysfs net checks
    if not test_proc_sys_net():
        print("RESULT proc_sys_net=FAIL")
        success = False
    else:
        print("RESULT proc_sys_net=PASS")

    # Run UDP queue pressure checks
    if not test_udp_queue_pressure():
        print("RESULT udp_queue_pressure=FAIL")
        success = False
    else:
        print("RESULT udp_queue_pressure=PASS")

    print("\n--- Verification Summary ---")
    if not success:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
