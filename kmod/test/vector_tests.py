#!/usr/bin/env python3
import fcntl
import os
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

    print("\n--- Verification Summary ---")
    if not success:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
