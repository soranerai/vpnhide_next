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

## v1.8.0

### Added
- Added diagnostic checks for loopback bind conflict and TrafficStats volume anomaly. Added NetworkStatsService system_server hooks to spoof TrafficStats and bypass detection.
- Added security_socket_bind kernel hook to silently redirect blocked loopback port binds to port 0, making bind conflict scanning succeed transparently.
- Added UDP Path MTU (PMTU) discovery active check and kernel-level socket spoofing hooks to hide PMTU bottlenecks
- Add ConnectivityDiagnostics as an isolated Java hook with its own toggle and localized description in the isolation settings
- Display passed checks counts ratio and partial status with premium blue theme on dashboard cards
- Implement registerConnectivityDiagnosticsCallback suppression hook in ConnectivityService to prevent target apps from receiving VPN reports
- Added automatic SQLite target migration from original app

### Changed
- Expanded kernel and Java active hooks mask to 32 bits for future-proof hook management
- Optimize Hook Isolation
- Replace Room ORM with raw SQLite
- Refined diagnostics screen styling with smooth rounded cards and status-aware detail tints
