#!/usr/bin/env python3
import argparse
import os
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(
        description="Compile, push, and run Rust VPN hide checks on an Android device."
    )
    parser.add_argument("-r", "--root", action="store_true", help="Run the checks as root (su)")
    parser.add_argument(
        "-u", "--uid", type=str, help="Run the checks under a specific UID (requires root)"
    )
    args = parser.parse_args()

    # 1. Resolve paths
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    native_dir = os.path.join(repo_root, "lsposed", "native")

    ndk_home = os.environ.get("ANDROID_NDK_HOME")
    if not ndk_home:
        # Try to infer from ANDROID_HOME
        android_home = os.environ.get("ANDROID_HOME")
        if android_home:
            ndk_dir = os.path.join(android_home, "ndk")
            if os.path.isdir(ndk_dir):
                versions = os.listdir(ndk_dir)
                if versions:
                    # Sort versions to get the highest
                    versions.sort()
                    ndk_home = os.path.join(ndk_dir, versions[-1])

    if not ndk_home or not os.path.isdir(ndk_home):
        print(
            "Error: ANDROID_NDK_HOME or ANDROID_HOME environment variables are not set or invalid.",
            file=sys.stderr,
        )
        print(
            "Please configure ANDROID_NDK_HOME to point to your Android NDK installation.",
            file=sys.stderr,
        )
        sys.exit(1)

    # Find the prebuilt toolchain bin directory
    toolchain_bin = os.path.join(ndk_home, "toolchains", "llvm", "prebuilt", "linux-x86_64", "bin")
    if not os.path.isdir(toolchain_bin):
        # Maybe on macOS or Windows?
        # NDK could have darwin-x86_64 or windows-x86_64
        for os_name in ["darwin-x86_64", "windows-x86_64", "linux-x86_64"]:
            test_path = os.path.join(ndk_home, "toolchains", "llvm", "prebuilt", os_name, "bin")
            if os.path.isdir(test_path):
                toolchain_bin = test_path
                break

    if not os.path.isdir(toolchain_bin):
        print(
            f"Error: Could not find prebuilt toolchain bin directory in {ndk_home}", file=sys.stderr
        )
        sys.exit(1)

    # Find aarch64-linux-androidXX-clang compiler
    linker_path = None
    # We prefer API level 29 (VPNHide minimum) up to 35
    for api_level in range(29, 36):
        clang_name = f"aarch64-linux-android{api_level}-clang"
        path = os.path.join(toolchain_bin, clang_name)
        if os.path.isfile(path):
            linker_path = path
            break

    if not linker_path:
        # Fallback to any aarch64 compiler
        for file in os.listdir(toolchain_bin):
            if file.startswith("aarch64-linux-android") and file.endswith("-clang"):
                linker_path = os.path.join(toolchain_bin, file)
                break

    if not linker_path:
        print(
            f"Error: Could not find aarch64-linux-android-clang compiler in {toolchain_bin}",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"Using NDK linker: {linker_path}")

    # 2. Compile the Rust binary
    print("Compiling Rust binary for Android (aarch64)...")
    env = os.environ.copy()
    env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = linker_path

    cargo_cmd = [
        "cargo",
        "build",
        "--target",
        "aarch64-linux-android",
        "--bin",
        "vpnhide_checks",
        "--release",
    ]
    res = subprocess.run(cargo_cmd, cwd=native_dir, env=env)
    if res.returncode != 0:
        print("Error: Cargo build failed.", file=sys.stderr)
        sys.exit(1)

    # 3. Locate compiled binary
    binary_path = os.path.join(
        native_dir, "target", "aarch64-linux-android", "release", "vpnhide_checks"
    )
    if not os.path.isfile(binary_path):
        print(f"Error: Compiled binary not found at {binary_path}", file=sys.stderr)
        sys.exit(1)

    # 4. Push to Android device
    device_dest = "/data/local/tmp/vpnhide_checks"
    print(f"Pushing binary to device: {device_dest}")
    push_cmd = ["adb", "push", binary_path, device_dest]
    res = subprocess.run(push_cmd)
    if res.returncode != 0:
        print("Error: adb push failed.", file=sys.stderr)
        sys.exit(1)

    # 5. Make executable
    chmod_cmd = ["adb", "shell", "chmod", "+x", device_dest]
    subprocess.run(chmod_cmd)

    # 6. Run the checks on device
    print("\nRunning diagnostic checks on device...")
    if args.uid:
        run_cmd = ["adb", "shell", "su", args.uid, "-c", device_dest]
    elif args.root:
        run_cmd = ["adb", "shell", "su", "-c", device_dest]
    else:
        run_cmd = ["adb", "shell", device_dest]

    # Run and stream output
    res = subprocess.run(run_cmd)
    sys.exit(res.returncode)


if __name__ == "__main__":
    main()
