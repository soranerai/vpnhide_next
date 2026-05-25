# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
