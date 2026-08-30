#!/usr/bin/env python3
"""Render the public iface-list matchers + their unit tests from
data/interfaces.toml.

Generates matchers for the public Kotlin and Rust consumers from one source.

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

# ---------------------------------------------------------------------------
# Rust emitter (lsposed/native)
# ---------------------------------------------------------------------------


def emit_rust(rules: list[Rule], tests: list[TestVector]) -> str:
    lines: list[str] = []
    lines.append(f"// {GENERATED_HEADER_LINE}")
    lines.append("")
    lines.append("#![allow(dead_code)]")
    lines.append("")
    lines.append("fn starts_with_ci(name: &[u8], prefix: &[u8]) -> bool {")
    lines.append("    if name.len() < prefix.len() {")
    lines.append("        return false;")
    lines.append("    }")
    lines.append("    for (i, &p) in prefix.iter().enumerate() {")
    lines.append("        if name[i].to_ascii_lowercase() != p {")
    lines.append("            return false;")
    lines.append("        }")
    lines.append("    }")
    lines.append("    true")
    lines.append("}")
    lines.append("")
    lines.append("fn starts_with_then_digits_ci(name: &[u8], prefix: &[u8]) -> bool {")
    lines.append("    if !starts_with_ci(name, prefix) {")
    lines.append("        return false;")
    lines.append("    }")
    lines.append("    let rest = &name[prefix.len()..];")
    lines.append("    !rest.is_empty() && rest.iter().all(|b| b.is_ascii_digit())")
    lines.append("}")
    lines.append("")
    lines.append("fn equals_ci(name: &[u8], other: &[u8]) -> bool {")
    lines.append("    if name.len() != other.len() {")
    lines.append("        return false;")
    lines.append("    }")
    lines.append("    name.iter()")
    lines.append("        .zip(other.iter())")
    lines.append("        .all(|(a, b)| a.to_ascii_lowercase() == *b)")
    lines.append("}")
    lines.append("")
    lines.append("fn contains_ci(haystack: &[u8], needle: &[u8]) -> bool {")
    lines.append("    if needle.is_empty() {")
    lines.append("        return true;")
    lines.append("    }")
    lines.append("    if needle.len() > haystack.len() {")
    lines.append("        return false;")
    lines.append("    }")
    lines.append("    for start in 0..=haystack.len() - needle.len() {")
    lines.append("        let window = &haystack[start..start + needle.len()];")
    lines.append("        if window")
    lines.append("            .iter()")
    lines.append("            .zip(needle.iter())")
    lines.append("            .all(|(a, b)| a.eq_ignore_ascii_case(b))")
    lines.append("        {")
    lines.append("            return true;")
    lines.append("        }")
    lines.append("    }")
    lines.append("    false")
    lines.append("}")
    lines.append("")
    lines.append(
        "/// True if the name matches any VPN-iface rule from data/interfaces.toml."
    )
    lines.append("pub fn matches_vpn(name: &[u8]) -> bool {")
    lines.append("    if name.is_empty() {")
    lines.append("        return false;")
    lines.append("    }")
    lines.append('    if equals_ci(name, b"gre0") || equals_ci(name, b"gretap0") {')
    lines.append("        return false;")
    lines.append("    }")
    for r in rules:
        if r.note:
            lines.append(f"    // {r.note}")
        if r.needle == "tun" and r.kind == "prefix":
            lines.append(
                '    if starts_with_ci(name, b"tun") && !starts_with_ci(name, b"tunl") {'
            )
            lines.append("        return true;")
            lines.append("    }")
            continue
        if r.kind == "exact":
            fn = "equals_ci"
        elif r.kind == "prefix":
            fn = "starts_with_ci"
        elif r.kind == "prefix_digits":
            fn = "starts_with_then_digits_ci"
        elif r.kind == "contains":
            fn = "contains_ci"
        lines.append(f"    if {fn}(name, {rust_byte_lit(r.needle)}) {{")
        lines.append("        return true;")
        lines.append("    }")
    lines.append("    false")
    lines.append("}")
    lines.append("")
    # Test module — generated assertions are wide; skip rustfmt rather
    # than wrap each assertion across 5 lines.
    lines.append("#[cfg(test)]")
    lines.append("#[rustfmt::skip]")
    lines.append("mod tests {")
    lines.append("    use super::*;")
    lines.append("")
    lines.append("    #[test]")
    lines.append("    fn generated_vectors() {")
    for t in tests:
        # `assert!(x, msg)` / `assert!(!x, msg)` instead of
        # `assert_eq!(x, true/false, msg)` — clippy::bool_assert_comparison
        # would otherwise fire on every generated row when contributors
        # run `cargo clippy --tests`.
        prefix = "" if t.is_vpn else "!"
        lines.append(
            f"        assert!({prefix}matches_vpn({rust_byte_lit(t.name)}), "
            f'"matches_vpn({t.name!r})");'
        )
    lines.append("    }")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


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
        REPO_ROOT
        / "lsposed"
        / "native"
        / "src"
        / "generated"
        / "iface_lists.rs": emit_rust(rules, tests),
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
