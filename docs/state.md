# Persistent state — every path the project touches

Reference catalogue for everyone (humans, agents) trying to answer
"where is this stored?" / "who reads X?" / "what survives a reboot?".

Grouped by **location prefix**, because the same path is usually
written by one component and read by another, and grouping by reader
or writer would split the same path across multiple places.

For each entry: format, who writes, who reads, lifetime, mode/owner/
SELinux label when relevant. File:line cites the source of truth.

> **Heads-up:** SELinux contexts shown here are `setfiles`/AOSP
> defaults observed on a Pixel running Magisk. Custom kernels or
> heavily modified ROMs may relabel things; if a write fails, check
> `dmesg | grep avc` first.

---

## 1. Module install dirs — `/data/adb/modules/vpnhide_*/`

These are the standard Magisk/KSU module dirs. Anything inside is
**wiped on module reinstall** — the root manager replaces the whole
tree from the zip. So all *user-managed* state lives outside, in the
persistent dirs of section 2.

### `/data/adb/modules/vpnhide_kmod/`
- `module.prop` — module metadata + stamped `gkiVariant=` and `version=`. Read by app dashboard (`DashboardData.kt:382` `parseModuleProp`).
- `post-fs-data.sh` — runs at boot, attempts `insmod vpnhide_kmod.ko`, writes diagnostics into the persistent dir.
- `service.sh` — runs after boot, reads `targets.txt`, resolves UIDs, writes `/proc/vpnhide_targets`.
- `vpnhide_kmod.ko` — the kernel module binary itself.

### `/data/adb/modules/vpnhide_zygisk/`
- `module.prop` — module metadata.
- `customize.sh` — install-time hook; seeds persistent dir, migrates legacy targets.
- `service.sh` — boot script; copies persistent `targets.txt` and `debug_logging` into module dir so the Zygisk loader's `get_module_dir()` fd sees them.
- `zygisk/arm64-v8a.so` — Rust cdylib injected into every forked app by NeoZygisk.
- `targets.txt` — **boot-time copy** of the canonical persistent file (`/data/adb/vpnhide_zygisk/targets.txt`). Loader reads via fd, not path. (`zygisk/module/service.sh`, `zygisk/src/lib.rs`)
- `debug_logging` — `"0"` or `"1"`. **Boot-time copy** of `/data/adb/vpnhide_zygisk/debug_logging` (canonical). The app also writes both paths directly via su on every toggle, so the module-dir mirror stays current between reinstall and reboot. Read by zygisk module on init via the module dir fd.

### `/data/adb/modules/vpnhide_ports/`
- `module.prop`.
- `customize.sh` — seeds persistent dir on install.
- `service.sh` — calls `vpnhide_ports_apply.sh` after netd is up.
- `vpnhide_ports_apply.sh` — main runtime script. Resolves observers → UIDs, builds & applies iptables rules. Also re-invoked by the app via su when the user taps Save.
- `uninstall.sh` — flushes `vpnhide_out` / `vpnhide_out6` chains.

---

## 2. Module persistent dirs — `/data/adb/vpnhide_*/`

These dirs are **outside** `/data/adb/modules/`, so module reinstalls
don't touch them. They survive Magisk/KSU updates, kernel upgrades,
and even uninstalling+reinstalling the corresponding module. Wiped
only by factory reset.

### `/data/adb/vpnhide_kmod/`
| File | Format | Writer | Reader | Lifetime |
|---|---|---|---|---|
| `targets.txt` | one pkg per line, `#` comments | app via su (Save in Protection); seeded by `kmod/module/customize.sh` | `kmod/module/service.sh` (boot, resolves to UIDs) | persistent |
| `load_status` | `key=value` per line: `timestamp`, `boot_id`, `uname_r`, `gki_variant`, `kmod_version`, `root_manager`, `kprobes`, `kretprobes`, `insmod_exit`, `loaded`, `insmod_stderr` | `kmod/module/post-fs-data.sh` | app dashboard (`KMOD_LOAD_STATUS_FILE` constant in `ShellUtils.kt`); `readKmodLoadStatus` in `DashboardData.kt:482` | overwritten each boot |
| `load_dmesg` | filtered `dmesg` excerpt (text) | `post-fs-data.sh` | dashboard (verbose error display) | overwritten each boot |

`boot_id` in `load_status` is compared against the current
`/proc/sys/kernel/random/boot_id` so the app can tell "this status
was written this boot" vs. "stale from last boot".

### `/data/adb/vpnhide_zygisk/`
| File | Format | Writer | Reader | Lifetime |
|---|---|---|---|---|
| `targets.txt` | one pkg per line | app via su; `zygisk/module/customize.sh` migrates from legacy in-module location | copied into module dir by `zygisk/module/service.sh` at boot | persistent |
| `debug_logging` | single byte `"0"` or `"1"` | app via su (`DebugLoggingPrefs.kt::writeDebugFlagFiles`, part of the persistent toggle fan-out) | copied into module dir by `zygisk/module/service.sh` at boot, where the .so reads it via `get_module_dir()` fd | persistent |

### `/data/adb/vpnhide_ports/`
| File | Format | Writer | Reader | Lifetime |
|---|---|---|---|---|
| `observers.txt` | one pkg per line | app via su; seeded by `portshide/module/customize.sh` | `vpnhide_ports_apply.sh` (boot + on Save) | persistent |

### `/data/adb/vpnhide_lsposed/`
| File | Format | Writer | Reader | Lifetime |
|---|---|---|---|---|
| `targets.txt` | one pkg per line | app via su (`ensureSelfInTargets` in `ShellUtils.kt:198` + Protection screens) | `kmod/module/service.sh` and `zygisk/module/service.sh` migrate-from on first boot if their own `targets.txt` is empty | persistent |

LSPosed has no module dir (it's not a Magisk module — it's
hooks installed into system_server by the Vector framework), so
everything lives in this persistent dir directly.

---

## 3. system_server-readable files — `/data/system/vpnhide_*`

This is the coordination channel between the app (writes via su) and
the LSPosed hooks running inside system_server. All files here are
**owned `root:system`, mode 0640, label `system_data_file`** — system_server
reads via the `system` group, untrusted apps fall to "other" and get
EACCES. The dir `/data/system/` itself is mode 0775 traversable by
all, so the per-file restriction matters; a plain 0644 here would be
enumerable + readable.

| File | Format | Writer | Reader | Lifetime |
|---|---|---|---|---|
| `vpnhide_uids.txt` | one UID per line (integer) | `kmod/module/service.sh` and `zygisk/module/service.sh` resolve `targets.txt` → UIDs at boot; app via su after Save | LSPosed hook in system_server; `HookEntry.kt:174` first-call read + FileObserver (`HookEntry.kt:346`) for live reload | persistent (rewritten at boot + on save) |
| `vpnhide_hidden_pkgs.txt` | one pkg per line | app via su when user picks "Apps to hide from PackageManager" | `PackageVisibilityHooks.kt:124` + FileObserver | persistent |
| `vpnhide_observer_uids.txt` | one UID per line | app via su | `PackageVisibilityHooks.kt:111` + FileObserver | persistent |
| `vpnhide_hook_active` | `key=value`: `version`, `boot_id`, `timestamp`, `aosp_sdk`, optional `broken_fields` | `HookEntry.kt:312-339` `writeHookStatusFile` (in system_server) | app dashboard (`DashboardData.kt:805` reads via su); compares `boot_id` to detect stale records | per-boot |
| `vpnhide_debug_logging` | single byte `"0"` or `"1"` | app via su (`DebugLoggingPrefs.kt::writeDebugFlagFiles`) | LSPosed hooks via `HookLog.reload` + FileObserver (`HookLog.kt:30-43`); `kmod/module/service.sh` re-seeds `/proc/vpnhide_debug` from this file at boot; surfaced as a Dashboard warning (`DashboardData.kt:991`) | persistent |

**FileObservers** in `HookEntry.kt` and `PackageVisibilityHooks.kt`
watch `/data/system/` for `CREATE | CLOSE_WRITE | MOVED_TO | MODIFY`
events. Saves from the app trigger inotify, which invalidates the
in-process cache, so a Save propagates to running system_server
hooks **without any IPC or restart**.

### Debug-logging fan-out

The "Debug logging" toggle in Diagnostics drives **four sinks** from
one writer (`DebugLoggingPrefs.kt::applyDebugLoggingRuntime`):

```
toggle ON/OFF
    │
    ▼
applyDebugLoggingRuntime(enabled)
    ├─ VpnHideLog.enabled = enabled                          (1) app process — instant, in-memory
    └─ writeDebugFlagFiles (one batched su):
         ├─ /data/system/vpnhide_debug_logging               (2) system_server — ~10ms via inotify FileObserver
         ├─ /data/adb/vpnhide_zygisk/debug_logging           (3a) zygisk persistent — survives module reinstall
         ├─ /data/adb/modules/vpnhide_zygisk/debug_logging   (3b) zygisk module-dir mirror — read by .so on next fork
         └─ /proc/vpnhide_debug                              (4) kmod — instant if loaded, else no-op
```

`/data/system/vpnhide_debug_logging` is the canonical persistent
source-of-truth on disk for everything except zygisk; zygisk has its
own canonical at `/data/adb/vpnhide_zygisk/debug_logging` because
the .so reads via the module-dir fd, not via system_data_file path.

Boot-time re-seeding makes the persistent toggle survive reboots
without the app needing to open:

- `kmod/module/service.sh` copies `/data/system/vpnhide_debug_logging`
  → `/proc/vpnhide_debug` (in-kernel state lost on every boot).
- `zygisk/module/service.sh` copies `/data/adb/vpnhide_zygisk/debug_logging`
  → `/data/adb/modules/vpnhide_zygisk/debug_logging` (module-dir
  mirror lost on every module reinstall).

`MainActivity.onCreate` re-propagates from SharedPrefs to all four
sinks at every app launch as a safety-net for "user reinstalled a
module mid-session and didn't reboot before opening the app".

Errors (`Log.e` / `HookLog.e` / Rust `error!`) always print
regardless of the toggle — these fire at most once per process /
hook install and are the only signal we have when "hooks didn't
attach"-class problems happen.

---

## 4. Kernel module ABI — `/proc/vpnhide_*`

Created by `kmod/vpnhide_kmod.c` at module init via `proc_create()`,
removed at module exit. Mode `0600`, root-only.

| Path | Format | Writer | Reader (kernel side) |
|---|---|---|---|
| `/proc/vpnhide_targets` | one UID per line; write replaces full set | root userspace: `kmod/module/service.sh` writes resolved UIDs at boot; LSPosed app via su after Save | `vpnhide_kmod.c` `targets_write` handler — caches UIDs in kernel memory, consulted by every kretprobe handler |
| `/proc/vpnhide_debug` | `"1"` enables, `"0"` disables verbose `pr_info` from kretprobes | app via `DebugLoggingPrefs.kt::writeDebugFlagFiles` (part of the persistent toggle fan-out, see § 3); `kmod/module/service.sh` re-seeds at boot from `/data/system/vpnhide_debug_logging` | `READ_ONCE(debug_enabled)` in every probe handler |

Both files are **per-boot, in-kernel state only**. Unloading the
module (or rebooting) wipes the in-kernel state; service.sh re-seeds
both at next boot — `/proc/vpnhide_targets` from
`/data/adb/vpnhide_kmod/targets.txt`, and `/proc/vpnhide_debug` from
`/data/system/vpnhide_debug_logging`.

---

## 5. App-process state — SharedPreferences and `filesDir`

### SharedPreferences `vpnhide_prefs`

Accessed via `context.getSharedPreferences("vpnhide_prefs", MODE_PRIVATE)`
in Kotlin. Keys currently in use:
- `debug_logging: Boolean` — Diagnostics toggle (`DebugLoggingPrefs.kt:21,27-30`).
- `last_seen_version: String` — for "what's new" changelog dialog.
- `help_collapsed_apps_tun: Boolean` and similar — collapse state for help accordions.

> ⚠️ **Vector LSPosed redirects this storage.**
>
> Because `dev.soranerai.vpnhidenext` is registered in LSPosed as its own
> module, the Vector framework hooks `Context.getSharedPreferences()`
> for the app's process and **transparently redirects reads/writes**
> to:
>
> ```
> /data/misc/<vector-uuid>/prefs/dev.soranerai.vpnhidenext/vpnhide_prefs.xml
> ```
>
> The `<vector-uuid>` is a row in `/data/adb/lspd/config/modules_config.db`.
> Owner is the app uid, SELinux label `xposed_data`.
>
> **Consequence when debugging:**
> `/data/data/dev.soranerai.vpnhidenext/shared_prefs/` will be empty even
> when the user has touched the toggle. Writing a fake `vpnhide_prefs.xml`
> at `/data/data/<pkg>/shared_prefs/` from a root shell has **no
> effect** — Vector ignores that path. To inspect or seed prefs:
>
> ```sh
> su -c "find /data/misc -name vpnhide_prefs.xml"
> ```
>
> This is Vector framework behaviour (the modern equivalent of the
> classic `XSharedPreferences` mechanism), not a vpnhide-specific
> quirk.

### `filesDir` — `/data/user/0/dev.soranerai.vpnhidenext/files/`

| File | Format | Writer | Reader | Lifetime |
|---|---|---|---|---|
| `vpnhide_zygisk_active` | `key=value`: `version`, `boot_id`, `pid`, `timestamp` | Zygisk module (`zygisk/src/lib.rs`) when the VPNHide Next app itself is forked under zygisk hooks | App reads from its own `filesDir` to verify zygisk is hooking the app process; compared against current `boot_id` to detect stale heartbeats; `cleanupStaleZygiskStatus` (`ShellUtils.kt:113`) deletes if stale | per-app-launch (overwritten on each fork) |

Owner is the app uid (no su involved on read; Zygisk runs in the
forked app process so it has DAC perms to write into the app's own
filesDir).

### `cacheDir` — `/data/user/0/dev.soranerai.vpnhidenext/cache/`

Scratch space for short-lived files. Currently used for:
- `vpnhide_lspd_modules_config.db` (`+ -wal`, `+ -shm`) — temporary copies of LSPosed's config DB pulled via su and SQLite-opened read-only, then deleted. See `readLsposedConfig` (`DashboardData.kt:529-612`).

---

## 6. iptables — `vpnhide_out` and `vpnhide_out6`

Two named chains in the `filter` / `OUTPUT` path, IPv4 and IPv6.
Defined in `portshide/module/vpnhide_ports_apply.sh:21-22`.

- **Created/populated** by `vpnhide_ports_apply.sh` via `iptables-restore`
  / `ip6tables-restore` (`--noflush` so other chains aren't touched).
- **Triggered**: at boot (`portshide/module/service.sh`), and on every
  Save in the Ports tab of the app (re-runs the apply script via su).
- **Removed** on module uninstall (`portshide/module/uninstall.sh`).
- **Live in kernel memory only** — no persistence across reboot;
  must be re-applied each boot.
- Per-UID rules: target observer apps' UIDs get REJECT for connections
  to `127.0.0.0/8` and `::1`.

The dashboard's "ports active" check is `iptables -L vpnhide_out -n`
in `DashboardData.kt:690` — chain existence implies the apply script
has run successfully this boot.

---

## 7. External / third-party paths the app reads

Not owned by us, but consulted for diagnostics or wiring.

| Path | Owner / Source | Purpose |
|---|---|---|
| `/data/adb/lspd/config/modules_config.db` (+ `-wal`, `-shm`) | LSPosed framework (LSPosed-Next / Vector) | Module enabled state, scope packages — read by `readLsposedConfig` in `DashboardData.kt:529` for dashboard display |
| `/data/misc/<vector-uuid>/prefs/<pkg>/` | Vector LSPosed | Redirected SharedPreferences — see § 5 |
| `/proc/sys/kernel/random/boot_id` | Linux kernel | Compared everywhere with stored `boot_id` fields to detect "is this record from the current boot?" |
| `/proc/version`, `/proc/modules`, `/proc/config.gz` | Linux kernel | Read by `kmod/module/post-fs-data.sh` for kernel diagnostics |
| `/proc/net/{route,ipv6_route,if_inet6,tcp,tcp6,udp,udp6,dev,fib_trie}` | Linux kernel | Read by `lsposed/native/src/` diagnostics + filtered by Zygisk hooks (`zygisk/src/hooks.rs`) — a memfd substitute is returned when a target app `openat`'s these |
| `/sys/class/net/`, `/sys/class/net/<iface>/operstate` | Linux kernel | `isVpnActiveBlocking` (`ShellUtils.kt:96`) iterates interfaces and checks operstate to detect "VPN is up right now" |

---

## 8. Boot-time write sequence (high-level)

```
post-fs-data.sh phase (root, before zygote):
  kmod/module/post-fs-data.sh
    → modprobe / insmod vpnhide_kmod.ko
    → write /data/adb/vpnhide_kmod/load_status
    → write /data/adb/vpnhide_kmod/load_dmesg

service.sh phase (root, after boot_completed-ish):
  kmod/module/service.sh
    → resolve /data/adb/vpnhide_kmod/targets.txt → UIDs
    → write /proc/vpnhide_targets
    → write /data/system/vpnhide_uids.txt
  zygisk/module/service.sh
    → cp /data/adb/vpnhide_zygisk/targets.txt → /data/adb/modules/vpnhide_zygisk/targets.txt
    → resolve /data/adb/vpnhide_lsposed/targets.txt → UIDs
    → append/merge into /data/system/vpnhide_uids.txt
  portshide/module/service.sh
    → wait for netd
    → /data/adb/modules/vpnhide_ports/vpnhide_ports_apply.sh
      → resolve /data/adb/vpnhide_ports/observers.txt → UIDs
      → iptables-restore --noflush  (creates vpnhide_out / vpnhide_out6)

system_server start (LSPosed framework injects):
  HookEntry.handleLoadPackage
    → install hooks
    → write /data/system/vpnhide_hook_active (with current boot_id)
    → start FileObservers on /data/system/

zygote forks an app (NeoZygisk):
  zygisk/src/lib.rs::on_load (in forked process before specialize)
    → read targets via module dir fd
  zygisk/src/lib.rs::post_app_specialize
    → if target: install libc hooks
    → if VPNHide Next app itself: write filesDir/vpnhide_zygisk_active
```

---

## 9. Lifetime cheat-sheet

| Lifetime class | Examples |
|---|---|
| **In-kernel only (per-boot, volatile)** | `/proc/vpnhide_targets`, `/proc/vpnhide_debug`, iptables `vpnhide_out{,6}` chains |
| **Per-boot** (overwritten each boot by service scripts) | `/data/adb/vpnhide_kmod/load_status`, `/data/adb/vpnhide_kmod/load_dmesg`, `/data/system/vpnhide_uids.txt`, `/data/system/vpnhide_hook_active` |
| **Per-app-launch** (overwritten on each fork) | `<app-filesDir>/vpnhide_zygisk_active` |
| **Persistent — survives reboot, module reinstall, app reinstall** | `/data/adb/vpnhide_*/targets.txt`, `/data/adb/vpnhide_ports/observers.txt`, `/data/system/vpnhide_hidden_pkgs.txt`, `/data/system/vpnhide_observer_uids.txt`, `/data/system/vpnhide_debug_logging` |
| **Persistent — survives reboot but wiped on module reinstall** | everything inside `/data/adb/modules/vpnhide_*/` (Magisk/KSU replaces the tree from the zip) |
| **Persistent — survives reboot but wiped on app reinstall** | SharedPrefs `vpnhide_prefs` — but stored at the Vector-redirected path under `/data/misc/<uuid>/prefs/` |
| **Wiped only by factory reset** | `/data/adb/vpnhide_*/` persistent dirs + their contents |
