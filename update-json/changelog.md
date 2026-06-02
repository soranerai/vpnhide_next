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

## v1.6.0

### Added
- Add getsockname diagnostic check to verify VPN hiding on connected sockets
- Implement getsockname spoofing via userspace IP service
- Intercept setsockopt(SO_MARK) calls to reset physical/non-VPN interface routing binds
- Added RTM_GETRULE, TCP_MAXSEG, and RTM_GETNEIGH checks to diagnostics suite
- Added dynamic kernel hook isolation screen to diagnostics for crash debugging
- - WifiInfo hooks in system_server: restore IP/SSID/BSSID redacted by Android 12+ privacy controls (fixes MTS detection on Wi-Fi)
- Suppress VPN-specific network callbacks for target apps in system_server (fixes MTS detection on cellular networks)
- Add new diagnostic checks in the companion app to verify VPN callback suppression and WifiInfo unredaction

### Fixed
- Fix critical kernel panic (Null dereference and invalid skb register mapping in GKI 6.1+ rt_fill_info)
- Implement robust score-based physical interface ranking to select default internet-routing interface (e.g. ccmni2 with DNS) rather than secondary cellular interfaces (e.g. ccmni1).
- Fix register mapping in rt_fill_info hook to prevent kernel panics on ARM64
- Fix setsockopt registers mapping for ARM64 kernels >= 6.4 (including 6.6 and 6.12)
- Hid routing policy database rules from target apps
