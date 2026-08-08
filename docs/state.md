# Persistent state

This document describes the current policy storage contract. New code must
not create target lists or coordination files outside the application-owned
configuration directory.

## Authoritative policy

The app stores one atomically replaced JSON file in Device Protected Storage:

```text
/data/user_de/0/dev.soranerai.vpnhidenext/files/vpnhide_config.json
```

The path is resolved from the application context and is not hardcoded in
callers. `AppDatabase` is the writer; `vpnhide-daemon` and `vpnhide-ctl load`
are the root-side readers.

The file contains `globalConfig`, `apps`, `portRules`, `massPortRules`, and
`ifacePrefixes`. `globalConfig.listMode` is `BLACKLIST` by default for
backward compatibility. An app entry is declarative: its package, user, UID
hint, and per-layer selections are passed to the backend. The backend queries
Package Manager and computes effective UID snapshots.

The frontend must never compute the allowlist complement, write UID target
files, or truncate the effective target set. The backend protects system and
privileged packages by default and rejects an oversized effective snapshot.
An app entry with `systemPolicyExplicit: true` may override that default for a
Package Manager-verified system package on a per-layer basis. Missing markers
retain the protected legacy behavior; root and core system appIds remain
ineligible targets.

## Apply flow

```text
Compose UI
  -> AppDatabase transaction
  -> AtomicFile rename of vpnhide_config.json
  -> vpnhide-ctl load <config> <manager_uid>
  -> VH_SET_POLICY
  -> immutable kernel snapshot
```

The daemon watches the same JSON directory and re-runs `load` after an
atomic update or a Package Manager change. A failed load leaves the previous
kernel snapshot active and is reported to the app.

Hook masks, debug state, interface prefixes, ports, and targets are all part
of the JSON policy. Intercept statistics are session-scoped runtime
diagnostics: the daemon samples cumulative kernel counters into a six-hour
in-memory ring and exposes hook and per-port history through the abstract
`vpnhide.stats.v1` socket. No statistics history or `stats_window` setting is
persisted.

## Runtime and diagnostics

The kernel state is volatile and is read through the module's read-only
diagnostic API. `/proc/vpnhide_targets` and the old per-component setters are
not frontend contracts.

The app may inspect module load status under:

```text
/data/adb/vpnhide_kmod/load_status
/data/adb/vpnhide_kmod/load_dmesg
```

These are boot diagnostics, not policy storage.

Hook status is exposed through the read-only control device where supported.
No policy or debug coordination file is written to `/data/system`.

The app reads kmod intercept history through the root-side
`vpnhide-ctl stats_history` helper. The helper connects to the daemon's
abstract `vpnhide.stats.v1` socket, returns interval deltas, and keeps the ring
in memory. The app does not connect to that socket directly; a root-helper
failure means statistics are temporarily unavailable and must not be shown as
an empty history.

## Migration

The JSON store can migrate the old SQLite database once. Old target files are
not read by the normal cache or save path. Existing installations that still
have legacy files must be migrated by an explicit, versioned migration before
the old files are removed; new code must not recreate them.

Backups include `global_config.listMode`. Missing mode in an old backup means
`BLACKLIST`.

## Lifetime

| State | Location | Lifetime |
|---|---|---|
| Declarative policy | app DE `files/vpnhide_config.json` | survives reboot, app-owned |
| Kernel effective snapshot | kernel memory via `VH_SET_POLICY` | until unload/reboot |
| Daemon reconciliation state | memory only | until daemon exits |
| Module diagnostics | `/data/adb/vpnhide_kmod/load_*` | overwritten per boot |

The app's cache and dashboard must label configured selections separately
from effective kernel targets, especially in `ALLOWLIST` mode.
