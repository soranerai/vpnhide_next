#!/usr/bin/env python3
"""Verify that an APK's arm64 UniFFI library exports every Kotlin FFI symbol."""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
BINDINGS = REPO_ROOT / "lsposed/app/src/main/kotlin/dev/soranerai/vpnhidenext/checks/vpnhide_checks.android.kt"
LIBRARY_PATH = "lib/arm64-v8a/libvpnhide_checks.so"
SYMBOL_RE = re.compile(r"external fun (uniffi_vpnhide_checks_[A-Za-z0-9_]+)")


def required_symbols() -> set[str]:
    return set(SYMBOL_RE.findall(BINDINGS.read_text(encoding="utf-8")))


def exported_symbols(library: Path) -> set[str]:
    readelf = shutil.which("readelf")
    if readelf is None:
        raise RuntimeError("readelf is required to verify UniFFI exports")
    result = subprocess.run(
        [readelf, "--wide", "--dyn-syms", str(library)],
        check=True,
        capture_output=True,
        text=True,
    )
    return {
        line.split()[-1]
        for line in result.stdout.splitlines()
        if " uniffi_vpnhide_checks_" in line
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="APK to inspect")
    args = parser.parse_args()

    try:
        with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory() as temp_dir:
            try:
                library = Path(temp_dir) / "libvpnhide_checks.so"
                library.write_bytes(apk.read(LIBRARY_PATH))
            except KeyError:
                print(f"error: {args.apk} does not contain {LIBRARY_PATH}", file=sys.stderr)
                return 1
            missing = sorted(required_symbols() - exported_symbols(library))
    except (OSError, RuntimeError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"error: unable to verify UniFFI exports: {error}", file=sys.stderr)
        return 1

    if missing:
        print("error: APK libvpnhide_checks.so is missing required UniFFI exports:", file=sys.stderr)
        print("\n".join(f"  {symbol}" for symbol in missing), file=sys.stderr)
        return 1

    print("UniFFI export verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
