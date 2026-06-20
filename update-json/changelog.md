## v1.11.0

### Added
- Added UserManager hooks to hide work profiles from targeted apps
- Implement RCU-based active VPN interface caching inside the kernel module driven by the daemon, eliminating runtime string matching and netdev traversals on hot BPF paths

### Changed
- Migrated remaining hardcoded UI strings to localized resources
- Migrated socket bind, connect, and getsockname hooks to top-level syscall wrappers to prevent bypasses via inlining
- Optimize all kretprobe hooks to return 1 early from entry handlers for non-target UIDs and non-matching requests, skipping return handler execution and releasing kretprobe resources instantly
- Optimize hot-path locking and memory copying (RCU for spoof IP, stack arrays for BPF, get/put_user for socket options)
- Optimize __sys_bpf hot paths by adding fast-path filter checks and rapid switch matching
- Optimized kretprobe hooks by skipping return handlers for non-target processes, significantly reducing CPU overhead
- Remove dev_get_by_index_rcu lookups from setsockopt and getsockopt hooks, using active VPN cache for SO_BINDTOIFINDEX instead
- Updated hook card titles in Hook Isolation screen to show user-friendly names instead of technical identifiers
- Updated Hook Isolation screen to match recent kernel-level hook refactorings and migrated all UI strings to localized resources
- Removed all /data/system config files, replacing file observers with direct /dev/vpnhide_ctrl kernel blocking reads.

### Fixed
- Add sock_common_getsockopt fallback hook to properly spoof TCP_MAXSEG when syscall hook is disabled
- Fix BPF map laundering instability for single lookup queries

### Removed
- Removed early-boot kernel crash detection and automatic hook mitigation logic

## v1.10.1

### Changed
- Migrate getsockopt intercept from sk_getsockopt/sock_getsockopt to __arm64_sys_getsockopt for better reliability against LTO inlining

## v1.10.0

### Changed
- Always block socket binding attempts (SO_BINDTODEVICE/SO_BINDTOIFINDEX) to VPN interfaces with ENODEV for target UIDs
- Optimize sys_setsockopt and sys_bpf hot paths by caching wrapper detection
- Updated kernel module hook descriptions, names and symbols in Hook Testing Screen

### Fixed
- Fix caching race conditions in system_server PackageManager hooks
- Fix ConnectivityService hook capture on some Android 16 builds
- Fix SO_BINDTODEVICE leak on kernels without sock_getsockopt/sock_setsockopt
- intercept setsockopt at the syscall
- Record statistics for sys_setsockopt intercepts to show up in diagnostics counters

### Removed
- Remove 'aikido' soft SO_BINDTODEVICE spoofing (zeroing out optlen)

## v1.9.7

### Fixed
- Replaced eBPF map ops hijacking with direct syscall filtering, and add batch lookup support for statistics laundering
- Prevent VPN apps from hiding themselves

## v1.9.6

### Changed
- Reverted dynamic symbol resolution in kernel module to prevent CFI panics on fresh kernels

### Fixed
- Optimize CPU and battery usage in kernel module, daemon, and lsposed hook
