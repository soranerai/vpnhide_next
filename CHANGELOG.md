# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## v1.12.1

### Changed
- Optimize JSON configuration storage to only persist apps with active protections

## v1.12.0

### Changed
- Cache physical interface name from daemon and eliminate redundant ConnectivityService IPC calls
- Migrate companion app local storage from SQLite to a single JSON configuration file in Device Protected Storage with a one-time startup migration screen
- Migrate kernel module configuration load path to read vpnhide_config.json directly on boot via parson, removing the sqlite3 CLI binary and reducing module zip size
- Optimize loadTargetUids by caching selfUid to prevent expensive reflection calls on every binder invocation
- Refactor ConnectivityService Network-handle hooks into a shared helper to eliminate per-method boilerplate
- App list loads instantly from disk cache on startup
- Hide successful diagnostics checks by default and display a simplified status card; show only failed checks if any check fails
- Require the kernel module (kmod) to be installed and active on app startup.
- Switched daemon interface detection from /proc/net/route heuristics to Active Probing via SO_BINDTODEVICE.

### Fixed
- Android 17: migrate NetworkCapabilities sanitization to public API so NC hook is not skipped on renamed private fields
- Bound netlink diagnostic recv loops and add SO_RCVTIMEO to prevent OOM crash when kmod suppresses NLMSG_DONE
- Fix name resolution of Work Profile applications on the Intercept Statistics and Scope screen
- Fix clearing of LSPosed framework hook statistics on dashboard reset
- Fix dashboard expanding multiple statistics cards for apps with the same package name in different user profiles by keying on UID instead of package name

### Removed
- Remove FAQ screen and button from the main app interface

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
