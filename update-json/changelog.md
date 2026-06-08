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

## v1.7.5

### Added
- Implement app settings backup and restore (.json) in diagnostics
- Implement manual statistics reset and automatic 30-minute stats expiration on the dashboard

### Changed
- Completely transition to SQLite-only configuration, eliminating legacy text files
- Exclude self package from dashboard Native targets count, and rename screen row toggles from Kernel/LSPosed to Native/Framework

### Fixed
- Fix cross-profile SecurityException during dashboard stats package resolution

## v1.7.0

### Added
- Add getNetworkForType() diagnostics check and AOSP ConnectivityService hook to hide VPN network type
- Implement native and framework-level real-time call intercept statistics on the Dashboard
- Add dynamic Java/Framework hook disabling on the fly to Diagnostics isolation screen

### Changed
- Make Dashboard module and protection status cards more compact and side-by-side

### Fixed
- Fix first-launch self-registration and prune uninstalled apps from target database
- Fix RTM_GETROUTE route leaking on Android 12 GKI 5.10

## v1.6.1

### Fixed
- Fix potential kernel panic on rt_fill_info hook, and implement stealth getsockopt spoofing via sock_common_getsockopt for IP_MTU, IPV6_MTU, and TCP_MAXSEG to prevent detection of MTU/MSS clamping.
