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

## v1.9.5

### Added
- Support Samsung Exynos mobile data interfaces (pdp*) in vpnhide_daemon

### Fixed
- Fix cellular socket spoofing and CLAT/IPv6-only fallback
- Resolve all kretprobe symbol names dynamically to fix registration failures due to LLVM suffixes/LTO

## v1.9.0

### Added
- Implemented kernel-level TrafficStats BPF map spoofing.
- Implemented auto filtering VpnServices and hiding VPN packages

### Changed
- Moved TrafficStats check to native slots, bump check version filter to API 35

### Fixed
- TrafficStats volume anomaly check now uses /proc/net/dev as ground truth to detect partial BPF-laundering failures that previously produced false-green results; iface_stats laundering implemented via two-pass BPF_MAP_LOOKUP_BATCH post-processing (collect VPN bytes, add to cover interface)
