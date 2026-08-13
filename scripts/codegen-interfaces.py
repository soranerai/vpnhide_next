#!/usr/bin/env python3
"""Render the public iface-list matchers + their unit tests from
data/interfaces.toml.

Generates matchers for the public kernel-module and Kotlin consumers.
The private native consumers generate their own Rust copy from the same
source in the private repository.

Re-run after editing data/interfaces.toml and commit the regenerated
files. CI's lint job re-runs the codegen and fails on drift.

Stdlib only: tomllib (Python 3.11+) is the only non-builtin import,
and that's stdlib too.
"""

from __future__ import annotations

import sys
import tomllib
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
TOML_PATH = REPO_ROOT / "data" / "interfaces.toml"

# Output paths — kept next to the code that consumes them so include /
# import paths stay short and obvious.
OUT_KMOD = REPO_ROOT / "kmod" / "generated" / "iface_lists.h"
OUT_KMOD_TEST = REPO_ROOT / "kmod" / "test_iface_lists.c"
OUT_LSP_KT = (
    REPO_ROOT
    / "lsposed"
    / "app"
    / "src"
    / "main"
    / "kotlin"
    / "dev"
    / "soranerai"
    / "vpnhidenext"
    / "generated"
    / "IfaceLists.kt"
)
OUT_LSP_KT_TEST = (
    REPO_ROOT
    / "lsposed"
    / "app"
    / "src"
    / "test"
    / "kotlin"
    / "dev"
    / "soranerai"
    / "vpnhidenext"
    / "generated"
    / "IfaceListsGeneratedTest.kt"
)

GENERATED_HEADER_LINE = (
    "AUTO-GENERATED from data/interfaces.toml — do not edit by hand. "
    "Regenerate with: python3 scripts/codegen-interfaces.py"
)


# ---------------------------------------------------------------------------
# rule normalization
# ---------------------------------------------------------------------------


VALID_KINDS = ("exact", "prefix", "prefix_digits", "contains")


class Rule:
    """One match rule from the toml, normalized to a known kind.

    kind ∈ VALID_KINDS.
    needle is the literal string (already lowercased — all targets
    fold case at match time).
    note is the human comment from the toml, copied into the
    generated source so reviewers see why each rule is there.
    """

    __slots__ = ("kind", "needle", "note")

    def __init__(self, kind: str, needle: str, note: str) -> None:
        self.kind = kind
        self.needle = needle
        self.note = note


class TestVector:
    __slots__ = ("name", "is_vpn")

    def __init__(self, name: str, is_vpn: bool) -> None:
        self.name = name
        self.is_vpn = is_vpn


def parse_rule(entry: dict[str, Any]) -> Rule:
    match = entry.get("match")
    if not isinstance(match, dict):
        raise SystemExit(f"entry missing or malformed `match`: {entry!r}")
    note = str(entry.get("note", "")).strip()

    keys = set(match.keys())
    if keys == {"exact"}:
        needle = str(match["exact"])
        kind = "exact"
    elif keys == {"prefix"}:
        needle = str(match["prefix"])
        kind = "prefix"
    elif keys == {"prefix", "suffix"}:
        suffix = str(match["suffix"])
        if suffix == "digits":
            kind = "prefix_digits"
        else:
            raise SystemExit(f"unsupported suffix {suffix!r}; expected digits")
        needle = str(match["prefix"])
    elif keys == {"contains"}:
        needle = str(match["contains"])
        kind = "contains"
    else:
        raise SystemExit(f"unsupported match shape {keys!r} in entry {entry!r}")

    if not needle:
        raise SystemExit(f"empty needle in entry {entry!r}")
    if not all(0x20 <= ord(c) < 0x7F for c in needle):
        raise SystemExit(f"non-ASCII needle {needle!r} in entry {entry!r}")
    return Rule(kind, needle.lower(), note)


def parse_test(entry: dict[str, Any]) -> TestVector:
    if "name" not in entry or "is_vpn" not in entry:
        raise SystemExit(f"[[test]] entry needs name and is_vpn: {entry!r}")
    name = str(entry["name"])
    if not all(0x20 <= ord(c) < 0x7F for c in name):
        raise SystemExit(f"non-ASCII test name {name!r}; the matcher itself is ASCII-only")
    return TestVector(name=name, is_vpn=bool(entry["is_vpn"]))


def load() -> tuple[list[Rule], list[TestVector]]:
    with TOML_PATH.open("rb") as f:
        data = tomllib.load(f)
    raw_rules = data.get("vpn") or []
    if not isinstance(raw_rules, list) or not raw_rules:
        raise SystemExit(f"{TOML_PATH}: missing or empty [[vpn]] table")
    rules = [parse_rule(e) for e in raw_rules]
    tests = [parse_test(e) for e in (data.get("test") or [])]
    return rules, tests


# ---------------------------------------------------------------------------
# escape helpers
# ---------------------------------------------------------------------------


def c_str_lit(s: str) -> str:
    out = ['"']
    for c in s:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif 0x20 <= ord(c) < 0x7F:
            out.append(c)
        else:
            out.append(f"\\x{ord(c):02x}")
    out.append('"')
    return "".join(out)


def rust_byte_lit(s: str) -> str:
    # Use byte-string with escapes so non-printable / quotes survive.
    out = ['b"']
    for c in s:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif 0x20 <= ord(c) < 0x7F:
            out.append(c)
        else:
            out.append(f"\\x{ord(c):02x}")
    out.append('"')
    return "".join(out)


def kt_str_lit(s: str) -> str:
    out = ['"']
    for c in s:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif c == "$":
            out.append("\\$")
        elif 0x20 <= ord(c) < 0x7F:
            out.append(c)
        else:
            out.append(f"\\u{ord(c):04x}")
    out.append('"')
    return "".join(out)


# ---------------------------------------------------------------------------
# C emitter (kmod)
# ---------------------------------------------------------------------------


def emit_kmod(rules: list[Rule]) -> str:
    """Render an inline header for the kernel module.

    Header is dual-target: builds in kernel (default) AND in userspace
    when __VPNHIDE_HOST_TEST is defined, so test_iface_lists.c can link
    the same matcher.
    """
    lines: list[str] = []
    lines.append(f"/* {GENERATED_HEADER_LINE} */")
    lines.append("#ifndef VPNHIDE_GENERATED_IFACE_LISTS_H")
    lines.append("#define VPNHIDE_GENERATED_IFACE_LISTS_H")
    lines.append("")
    lines.append("#ifdef __KERNEL__")
    lines.append("# include <linux/string.h>")
    lines.append("# include <linux/ctype.h>")
    lines.append("# include <linux/types.h>")
    lines.append("#else")
    lines.append("# include <ctype.h>")
    lines.append("# include <stdbool.h>")
    lines.append("# include <stddef.h>")
    lines.append("# include <string.h>")
    lines.append("#endif")
    lines.append("")
    lines.append("static inline bool vpnhide_iface_starts_with_ci(")
    lines.append("\tconst char *name, const char *prefix)")
    lines.append("{")
    lines.append("\tsize_t i;")
    lines.append("\tfor (i = 0; prefix[i]; i++) {")
    lines.append("\t\tif (!name[i])")
    lines.append("\t\t\treturn false;")
    lines.append("\t\tif (tolower((unsigned char)name[i]) !=")
    lines.append("\t\t    (unsigned char)prefix[i])")
    lines.append("\t\t\treturn false;")
    lines.append("\t}")
    lines.append("\treturn true;")
    lines.append("}")
    lines.append("")
    lines.append("static inline bool vpnhide_iface_starts_with_then_digits_ci(")
    lines.append("\tconst char *name, const char *prefix)")
    lines.append("{")
    lines.append("\tsize_t i;")
    lines.append("\tif (!vpnhide_iface_starts_with_ci(name, prefix))")
    lines.append("\t\treturn false;")
    lines.append("\ti = strlen(prefix);")
    lines.append("\tif (!name[i])")
    lines.append("\t\treturn false;")
    lines.append("\tfor (; name[i]; i++)")
    lines.append("\t\tif (name[i] < '0' || name[i] > '9')")
    lines.append("\t\t\treturn false;")
    lines.append("\treturn true;")
    lines.append("}")
    lines.append("")
    lines.append("static inline bool vpnhide_iface_equals_ci(")
    lines.append("\tconst char *name, const char *other)")
    lines.append("{")
    lines.append("\tsize_t i;")
    lines.append("\tfor (i = 0; other[i]; i++) {")
    lines.append("\t\tif (!name[i])")
    lines.append("\t\t\treturn false;")
    lines.append("\t\tif (tolower((unsigned char)name[i]) !=")
    lines.append("\t\t    (unsigned char)other[i])")
    lines.append("\t\t\treturn false;")
    lines.append("\t}")
    lines.append("\treturn name[i] == '\\0';")
    lines.append("}")
    lines.append("")
    lines.append("static inline bool vpnhide_iface_contains_ci(")
    lines.append("\tconst char *name, const char *needle)")
    lines.append("{")
    lines.append("\tsize_t nlen = strlen(needle);")
    lines.append("\tsize_t i, j;")
    lines.append("\tif (nlen == 0)")
    lines.append("\t\treturn true;")
    lines.append("\tfor (i = 0; name[i]; i++) {")
    lines.append("\t\tfor (j = 0; j < nlen; j++) {")
    lines.append("\t\t\tif (!name[i + j])")
    lines.append("\t\t\t\treturn false;")
    lines.append("\t\t\tif (tolower((unsigned char)name[i + j]) !=")
    lines.append("\t\t\t    (unsigned char)needle[j])")
    lines.append("\t\t\t\tbreak;")
    lines.append("\t\t}")
    lines.append("\t\tif (j == nlen)")
    lines.append("\t\t\treturn true;")
    lines.append("\t}")
    lines.append("\treturn false;")
    lines.append("}")
    lines.append("")
    lines.append("static inline bool vpnhide_iface_is_vpn(const char *name)")
    lines.append("{")
    lines.append("\tif (!name || !name[0])")
    lines.append("\t\treturn false;")
    for r in rules:
        if r.note:
            lines.append(f"\t/* {r.note} */")
        if r.needle == "tun" and r.kind == "prefix":
            lines.append(
                """\tif (vpnhide_iface_starts_with_ci(name, "tun") &&
                 !vpnhide_iface_starts_with_ci(name, "tunl"))"""
            )
            lines.append("\t\treturn true;")
            continue
        if r.kind == "exact":
            fn = "vpnhide_iface_equals_ci"
        elif r.kind == "prefix":
            fn = "vpnhide_iface_starts_with_ci"
        elif r.kind == "prefix_digits":
            fn = "vpnhide_iface_starts_with_then_digits_ci"
        elif r.kind == "contains":
            fn = "vpnhide_iface_contains_ci"
        lines.append(f"\tif ({fn}(name, {c_str_lit(r.needle)}))")
        lines.append("\t\treturn true;")
    lines.append("\treturn false;")
    lines.append("}")
    lines.append("")
    lines.append("#endif /* VPNHIDE_GENERATED_IFACE_LISTS_H */")
    lines.append("")
    return "\n".join(lines)


def emit_kmod_test(tests: list[TestVector]) -> str:
    """Render a userspace test driver.

    Built in CI's lint job (gcc on the host). Includes the same
    iface_lists.h that the kernel module uses, but compiles with
    libc headers via the !__KERNEL__ branch of the header guard.
    """
    lines: list[str] = []
    lines.append(f"/* {GENERATED_HEADER_LINE} */")
    lines.append("/*")
    lines.append(" * Userspace test driver for generated/iface_lists.h.")
    lines.append(" * Build: gcc -O2 -Wall -Werror -o test_iface_lists test_iface_lists.c")
    lines.append(" * Run: ./test_iface_lists  (exit 0 on success, 1 on failure)")
    lines.append(" */")
    lines.append("")
    lines.append("#include <stdbool.h>")
    lines.append("#include <stdio.h>")
    lines.append("")
    lines.append('#include "generated/iface_lists.h"')
    lines.append("")
    lines.append("static int failures;")
    lines.append("")
    lines.append("static void check(const char *name, bool expected)")
    lines.append("{")
    lines.append("\tbool got = vpnhide_iface_is_vpn(name);")
    lines.append("\tif (got != expected) {")
    lines.append('\t\tfprintf(stderr, "FAIL: vpnhide_iface_is_vpn(\\"%s\\") = %s, expected %s\\n",')
    lines.append('\t\t\tname, got ? "true" : "false", expected ? "true" : "false");')
    lines.append("\t\tfailures++;")
    lines.append("\t}")
    lines.append("}")
    lines.append("")
    lines.append("int main(void)")
    lines.append("{")
    for t in tests:
        expected = "true" if t.is_vpn else "false"
        lines.append(f"\tcheck({c_str_lit(t.name)}, {expected});")
    lines.append("")
    lines.append("\tif (failures) {")
    lines.append('\t\tfprintf(stderr, "%d test(s) failed\\n", failures);')
    lines.append("\t\treturn 1;")
    lines.append("\t}")
    lines.append(f'\tprintf("OK: {len(tests)} vectors passed\\n");')
    lines.append("\treturn 0;")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


# Rust generation lives in the private repository.
# ---------------------------------------------------------------------------
# Kotlin emitter (production code + separate test class)
# ---------------------------------------------------------------------------


def emit_kotlin(rules: list[Rule]) -> str:
    lines: list[str] = []
    lines.append(f"// {GENERATED_HEADER_LINE}")
    lines.append("")
    lines.append("package dev.soranerai.vpnhidenext.generated")
    lines.append("")
    lines.append("internal object IfaceLists {")
    lines.append("    /** True if `name` looks like a VPN tunnel per data/interfaces.toml. */")
    lines.append("    fun isVpnIface(name: String): Boolean {")
    lines.append("        if (name.isEmpty()) return false")
    lines.append("        val n = name.lowercase()")
    for r in rules:
        if r.note:
            lines.append(f"        // {r.note}")
        if r.needle == "tun" and r.kind == "prefix":
            lines.append('        if (n.startsWith("tun") && !n.startsWith("tunl")) return true')
            continue
        if r.kind == "exact":
            cond = f"n == {kt_str_lit(r.needle)}"
        elif r.kind == "prefix":
            cond = f"n.startsWith({kt_str_lit(r.needle)})"
        elif r.kind == "prefix_digits":
            lit = kt_str_lit(r.needle)
            cond = (
                f"n.startsWith({lit}) && "
                f"n.length > {len(r.needle)} && "
                f"n.substring({len(r.needle)}).all {{ it.isDigit() }}"
            )
        elif r.kind == "contains":
            cond = f"n.contains({kt_str_lit(r.needle)})"
        lines.append(f"        if ({cond}) return true")
    lines.append("        return false")
    lines.append("    }")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def emit_kotlin_test(tests: list[TestVector]) -> str:
    lines: list[str] = []
    lines.append(f"// {GENERATED_HEADER_LINE}")
    lines.append("")
    lines.append("package dev.soranerai.vpnhidenext.generated")
    lines.append("")
    lines.append("import org.junit.Assert.assertEquals")
    lines.append("import org.junit.Test")
    lines.append("")
    lines.append("class IfaceListsGeneratedTest {")
    lines.append("    @Test")
    lines.append("    fun `generated vectors`() {")
    for t in tests:
        expected = "true" if t.is_vpn else "false"
        lines.append(
            f"        assertEquals({kt_str_lit(t.name)}, {expected}, "
            f"IfaceLists.isVpnIface({kt_str_lit(t.name)}))"
        )
    lines.append("    }")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


def write_if_changed(path: Path, content: str) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_text(encoding="utf-8") == content:
        return False
    path.write_text(content, encoding="utf-8")
    return True


def main() -> int:
    rules, tests = load()
    outputs = {
        OUT_KMOD: emit_kmod(rules),
        OUT_KMOD_TEST: emit_kmod_test(tests),
        OUT_LSP_KT: emit_kotlin(rules),
        OUT_LSP_KT_TEST: emit_kotlin_test(tests),
    }
    changed = []
    for path, content in outputs.items():
        if write_if_changed(path, content):
            changed.append(path.relative_to(REPO_ROOT))

    if changed:
        print("Regenerated:")
        for p in changed:
            print(f"  {p}")
    else:
        print("All generated files already up to date.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
