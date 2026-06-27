#!/usr/bin/env python3
"""Run all repo-wide lints and formatters locally.

Matches the CI lint pipeline defined in `.github/workflows/ci.yml`.
Supports formatting (autofix) by default, and a strict `--check` mode.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

# Root directory of the repository
ROOT_DIR = Path(__file__).resolve().parent.parent


def run_command(
    args: list[str], cwd: Path = ROOT_DIR, check: bool = True
) -> subprocess.CompletedProcess[str]:
    """Run a subprocess command and display it."""
    print(f"--> Running: {' '.join(args)} (in {cwd.relative_to(ROOT_DIR) or '.'})")
    try:
        return subprocess.run(args, cwd=cwd, check=check, text=True, capture_output=False)
    except subprocess.CalledProcessError as e:
        print(f"Error: Command failed with exit code {e.returncode}")
        if not check:
            raise e
        sys.exit(e.returncode)


def is_tool_available(name: str) -> bool:
    """Check if a tool is available in PATH."""
    return shutil.which(name) is not None


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run local lints and formatters matching the CI pipeline."
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Only check files without modifying them (CI mode)",
    )
    parser.add_argument(
        "--skip-gradle",
        action="store_true",
        help="Skip slow Gradle linting/testing tasks",
    )
    parser.add_argument(
        "--skip-rust",
        action="store_true",
        help="Skip Rust linting/testing tasks",
    )
    args = parser.parse_args()

    # Keep track of failed checks if we want to run all and report at the end
    failed = False

    print("=== Step 1: Codegen drift check ===")
    try:
        run_command([sys.executable, "scripts/codegen-interfaces.py"])
        # Check for modifications in data/
        diff_res = subprocess.run(["git", "diff", "--exit-code", "data/"], cwd=ROOT_DIR)
        if diff_res.returncode != 0:
            print("Error: Interfaces codegen drifted from data/!")
            failed = True
    except Exception as e:
        print(f"Error checking codegen drift: {e}")
        failed = True

    print("\n=== Step 2: Python (ruff) ===")
    if is_tool_available("ruff"):
        # Format
        ruff_fmt = ["ruff", "format"]
        if args.check:
            ruff_fmt.append("--check")
        try:
            run_command(ruff_fmt)
        except subprocess.CalledProcessError:
            failed = True

        # Check / Lint
        ruff_check = ["ruff", "check"]
        if not args.check:
            ruff_check.append("--fix")
        try:
            run_command(ruff_check)
        except subprocess.CalledProcessError:
            failed = True
    else:
        print("Warning: 'ruff' not found. Skipping Python lint/format.")

    print("\n=== Step 3: Rust (lsposed native) ===")
    if args.skip_rust:
        print("Skipping Rust checks as requested.")
    elif is_tool_available("cargo"):
        rust_dir = ROOT_DIR / "lsposed/native"
        # fmt
        rust_fmt = ["cargo", "fmt"]
        if args.check:
            rust_fmt.append("--check")
        try:
            run_command(rust_fmt, cwd=rust_dir)
        except subprocess.CalledProcessError:
            failed = True

        # clippy
        if is_tool_available("cargo-ndk"):
            try:
                run_command(
                    [
                        "cargo",
                        "ndk",
                        "-t",
                        "arm64-v8a",
                        "clippy",
                        "--tests",
                        "--",
                        "-D",
                        "warnings",
                    ],
                    cwd=rust_dir,
                )
            except subprocess.CalledProcessError:
                failed = True
        else:
            print("Warning: 'cargo-ndk' not found. Falling back to host cargo clippy.")
            try:
                run_command(
                    [
                        "cargo",
                        "clippy",
                        "--tests",
                        "--",
                        "-D",
                        "warnings",
                    ],
                    cwd=rust_dir,
                )
            except subprocess.CalledProcessError:
                failed = True

        # test
        try:
            run_command(["cargo", "test"], cwd=rust_dir)
        except subprocess.CalledProcessError:
            failed = True
    else:
        print("Warning: 'cargo' not found. Skipping Rust checks.")

    print("\n=== Step 4: Shell scripts (shellcheck) ===")
    if is_tool_available("shellcheck"):
        shell_files = [
            "kmod/module/customize.sh",
            "kmod/module/post-fs-data.sh",
            "kmod/module/service.sh",
            "scripts/clean-device.sh",
            "scripts/update-json.sh",
            "kmod/test/run.sh",
            "kmod/test/build-kernel.sh",
            "kmod/test/init.sh",
        ]
        # Filter files that exist
        existing_shell_files = [f for f in shell_files if (ROOT_DIR / f).exists()]
        if existing_shell_files:
            try:
                run_command(["shellcheck", "-x", "-e", "SC2034,SC3043"] + existing_shell_files)
            except subprocess.CalledProcessError:
                failed = True
    else:
        print("Warning: 'shellcheck' not found. Skipping shell script lint.")

    print("\n=== Step 5: C (clang-format + host test) ===")
    # clang-format
    if is_tool_available("clang-format"):
        c_fmt = ["clang-format"]
        if args.check:
            c_fmt.extend(["--dry-run", "--Werror"])
        else:
            c_fmt.append("-i")
        c_fmt.append("kmod/vpnhide_kmod.c")
        try:
            run_command(c_fmt)
        except subprocess.CalledProcessError:
            failed = True
    else:
        print("Warning: 'clang-format' not found. Skipping C formatting.")

    # host test
    if is_tool_available("gcc"):
        test_bin = ROOT_DIR / "kmod/test_iface_lists"
        try:
            run_command(
                [
                    "gcc",
                    "-O2",
                    "-Wall",
                    "-Werror",
                    "-o",
                    str(test_bin),
                    "kmod/test_iface_lists.c",
                ]
            )
            run_command([str(test_bin)])
        except subprocess.CalledProcessError:
            failed = True
        finally:
            if test_bin.exists():
                test_bin.unlink()
    else:
        print("Warning: 'gcc' not found. Skipping host C test.")

    print("\n=== Step 6: Kotlin (ktlint + gradle) ===")
    # ktlint check/format
    if is_tool_available("ktlint"):
        ktlint_cmd = ["ktlint"]
        if not args.check:
            ktlint_cmd.append("-F")
        ktlint_cmd.append("lsposed/app/src/**/*.kt")
        try:
            run_command(ktlint_cmd)
        except subprocess.CalledProcessError:
            failed = True
    else:
        print("Warning: 'ktlint' not found. Skipping Kotlin code style checks.")

    # gradle tests & lint
    if args.skip_gradle:
        print("Skipping slow Gradle tasks.")
    else:
        gradlew = ROOT_DIR / "lsposed/gradlew"
        if gradlew.exists():
            try:
                run_command(
                    ["./gradlew", ":app:lintDebug", ":app:testDebugUnitTest"],
                    cwd=ROOT_DIR / "lsposed",
                )
            except subprocess.CalledProcessError:
                failed = True
        else:
            print("Warning: gradlew wrapper not found in lsposed/.")

    if failed:
        print("\n[!] Some lint checks or tests FAILED.")
        return 1

    print("\n[+] All checks and formatters completed successfully!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
