# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

## v1.5.0

### Added
- Add NetworkCapabilities signal strength and bandwidth checks to diagnostics with stealth masking
- Added getsockopt SO_BINDTODEVICE and inet_diag socket diagnostics to native checks screen
- Implement dynamic Network netId replacement with physical network to prevent cross-id leakage

### Changed
- Consolidated diagnostic checks on the screen
- Implement dynamic physical network properties propagation and add Wi-Fi state/WifiInfo diagnostic checks
- Moved all Xposed logs under the debug flag

### Fixed
- Fix Java-level VPN interface detection leak by dynamically redirecting to physical network properties
- Fix false-positive VPN detection in some apps (e.g. MTS)
- Fix loopback port bypass via 0.0.0.0, loopback subnets, IPv6 wildcard, and IPv4-mapped IPv6 loopback addresses

## v1.4.1

### Fixed
- rainbow hehe detection fix

## v1.4.0

### Added
- Added NetworkCallback check to Diagnostics

### Fixed
- Fix DNS leak of target/VPN interfaces in LinkProperties hooks
- Fixed NetworkCallback push-model and VpnService.prepare VPN detection leaks

## v1.3.0

### Changed
- Some ui fixes
- Custom interfaces hide ability
- Migrated boot-time rule application to SQLite database for faster startup
- Second stage of migration to Room

## v1.2.5

### Added
- Full support for Work Profile and secondary users with visual distinction and profile filtering

### Changed
- Improved app responsiveness by pre-loading application lists at startup
- Significantly improved settings saving performance

### Fixed
- Fixed incorrect label color for mass rules
- Fixed settings restore after reboot
- Restoration of protection targets and port rules after reboot

### Removed
- Removed unstable VPN routing bypass logic

## v1.2.0

## v1.1.0

### Added
- Granular Port Hiding: Ability to hide specific local ports from targeted applications via kernel-level socket filtering (connect() hook)
- Custom Rule Sets: Support for port ranges (e.g., 8080-8090) and protocol selection (TCP, UDP, or both) per application
- Enhanced UI: New interactive port rules editor with protocol toggles and simplified range management
- Memory Stability: Switched to virtual memory allocation (kvmalloc) in the kernel for large rule sets, preventing ENOMEM on fragmented systems

## v1.0.0

### Added
- Deep redesign and optimization: Completely reworked interface (skeleton, async loading) and optimized code
- Flexible sorting: Added the ability to sort applications properly
- Hiding anonymous TUN routes: Exclusion of TUN from route requests
- Kernel-level bind bypass: Ability to route packets directly, bypassing any application binds at the kernel level
- Maximum stealth: Complete removal of /proc/ files accessible to all applications, eliminating module detection via the file system
