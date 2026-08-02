# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## v2.2.2

### Changed
- Policy changes are now applied through the atomic JSON API; legacy target-file synchronization was removed, and Hide/Show modes were added.
- Compatibility is now checked using an explicit component matrix with a runtime fallback for the native version.
- The Native card shows the version of the running kernel module
- The number of targets is now unlimited
- Intercept statistics history is now provided by the daemon and kept for the current session.

### Fixed
- VPN-app hiding now accounts for the calling app and preserves the manager's own VPN services.
- LSPosed network hooks now consistently use the physical interface selected by the daemon.
- Make protection modes and port rules easier to understand, with clearer Hide/Show controls, help text, allowlist port rules, and consistent refresh feedback

## v2.2.0

### Added
- Added built-in mode kernel integration branch support and in-app announcement screen for kmod users

### Fixed
- Resolve native module and control tool paths dynamically to support custom module folders (e.g. vpnhide_kpatch).
- Fixed detection via system call (syscall) timing attacks in the built-in mode

## v2.1.3

### Fixed
- fixed path hiding

## v2.1.2

## v2.1.1

## v2.1.0

### Added
- App Picker: with the "Russian apps only" filter active, a new "Protect all shown" action stages full protection (kmod+LSPosed+port hiding) for every filtered app in one tap instead of toggling each one by hand.
- App Settings' per-app Hook Isolation screen now has Min/Standard/Max quick-preset buttons for the kernel hook mask, matching the levels already on the Dashboard, instead of only raw per-checkbox editing or all-on/all-off.
- Background protection health check: notifies if the kernel module or LSPosed hooks fail to activate after a reboot for apps you've already configured.
- Diagnostics can now run its full 44-check battery without a real VPN connected: when none is active, it silently raises a local-only test tunnel (own traffic only, no real packets sent, auto-stops right after the run) just for the duration of the check, then tears it down — no button, no prompt of our own.
- New Settings toggle: "Auto-test without VPN" lets you opt out of the automatic self-test tunnel diagnostics raises when no real VPN is connected.
- Settings now flags when the app isn't exempt from battery optimization and links to the system dialog to fix it, so background update/health checks aren't silently delayed or killed by OEM battery management.

### Changed
- Make hook status cards always clickable, scroll to bottom for Framework checks, and change status indicator to help icon

### Fixed
- Lock UI font scale to 1.0 to prevent layout issues on devices with large font scaling settings
- Clip statistics cards to rounded shape to fix tap animation boundaries
- Refresh dashboard and diagnostics cache when targets are saved on the protection tab
- Truncate long hook/vector labels with ellipsis in stats, and disable expand behavior for cards with only port triggers

## v2.0.0

### Added
- Add new native detection checks (system properties, alternative ioctls, direct syscalls, traceroute/RTT, /proc/sys/net/conf)
- Add IPv6 index sub-checks: multicast capability oracle, NDP tunnel oracle, hardware qdisc pressure test
- Add new diagnostics checks for ARP timeout, Broadcast domain rejection, and Hardware GSO asymmetry.
- Add UDP queue pressure diagnostics check (1000 sends success rate) to detect virtual VPN network queues
- Added TCP_INFO MSS spoof, RTM_GETQDISC filter, SO_TIMESTAMPING hw-bit strip, fib_trie hook, and four new detection checks (check_tcp_info_mss, check_qdisc_by_ifindex, check_timestamping_hw, check_rtm_getlink_trim_oracle)
- Added a standalone Rust binary and an automation script (./scripts/run-native-checks.py) to run native diagnostics directly on a device via ADB.
- Add protection level selector (Min / Avg / Max) on the Dashboard screen for quick kernel hook preset switching
- kmod: add two new hooks hardening against check_ipv6_link_local_bruteforce — inet6_bind link-local scope_id probe suppression (Hook 12d: intercepts AF_INET6 bind(fe80::, scope_id=VPN_idx) for target UIDs and returns ENODEV, hiding VPN interface indices from the Pass 1 blind bruteforce; kretprobe on inet6_bind since uaddr is already kernel-space by then) and a udpv6_sendmsg hook that suppresses the IPv6 NDP oracle and qdisc-flood detects
- Add scripts/build-app.sh to build the native lib in the private repo and assemble the lsposed APK locally
- Per-app hook isolation: override the kernel and framework hook mask for an individual app instead of only the global protection level, via a new settings icon on each app card in the tunnels list.
- Add a background job that checks for app updates and sends a notification when a new version is available
- Add a configurable statistics retention period (30 minutes to unlimited) and a new Intercept Summary dashboard card with a native/LSPosed/ports breakdown
- Add a toggle for background update checks to Experimental Features in Settings (enabled by default)
- Dashboard: intercept summary card has a Details link to the Statistics tab, and native/framework protection cards jump to diagnostics details (scrolled to the failed checks) when a check has failed
- Kernel Hooks, Framework Hooks and Ports (per-app and global settings), and the Dashboard/Protection/Statistics main tabs, can now be swiped between instead of only tapped
- Replaced the port rule dialog with a full-screen editor: an icon-badge header, pink-tinted text fields matching the Ports tab, a pill-style protocol selector, and inline warning banners instead of stock Material styling — plus a Material predictive-back animation (holding the system back gesture shrinks and slides the current screen aside to reveal what's underneath) shared with the port rule, per-app/global settings, Settings, and Diagnostics detail screens

### Changed
- Decouple kernel module from static interface name prefix lists, using active VPN name and index cache from the daemon instead
- Exclude inactive (DOWN) network interfaces from detection checks
- Add copy to clipboard on long tap for diagnostics check cards.
- Implement kernel-level UDP sendmsg rate limiting and delay to simulate physical network queue limits for target UIDs
- Remove red card highlighting on diagnostics checks failure
- check_ipv6_link_local_bruteforce: add if_indextoname resolution and an IPv6 PMTU oracle (EMSGSIZE on a 1450-byte sendto confirms a hidden tunnel even when netlink listing is filtered); add a fallback probe path for when anonymous_indices is empty (kernel intercepting if_indextoname) that automatically probes the 10 indices beyond the highest active one, with Passes 2-4 using an EINVAL-only multicast criterion on the fallback pool (results annotated [fallback]); add a SIOCGIFNAME ioctl fallback (Step 1b, via dev_ioctl() which kmod may not hook unlike /sys/class/net) and a SIOCGIFHWADDR hardware-type probe (Step 1c) to Pass 1, proving the absence of L2 hardware via ARPHRD_NONE/ARPHRD_PPP regardless of interface name obfuscation
- Make dashboard protection checks asynchronous relative to startup and show loading indicator
- Make individual diagnostic checks run in parallel with live progress updates under details fold
- Throttle diagnostic progress state updates to eliminate UI stuttering and keep check list collapsed by default during execution
- Tune UDP rate limiter bucket capacity and timing for more realistic queue simulation
- Fix hook type assignments in kmod stats (ioctl/netlink/proc/sockopt/connect/getname), add pull-to-refresh on Diagnostics page, rename check labels to accurate syscall names
- ProtectionLevelCard now animates its card background color with the active level (blue/green/gold tints); checks are skipped when no active VPN is detected, module cards show grey with -/- and diagnostics shows a static notice without a details button
- Skip diagnostic checks for disabled kernel hooks: checks that require inactive hooks now show grey 'Skipped' status instead of failing; protection counts on the dashboard exclude skipped checks and never show 'Partial' when all active hooks pass
- Use custom SVG icon for maximum protection level
- Diagnostics details now group native checks into Classic/Advanced/Extreme tiers under separate Framework and Native sections
- Redesigned the per-app hook and port settings screens (kernel hooks, framework hooks, ports) to match the app's expressive color language: kernel hooks are tinted blue, framework hooks purple, and ports pink, with shared pill-shaped tab switchers, tinted rule/hook cards, and reused settings components instead of stock Material controls
- Redesigned the Statistics and Settings screens, and gave the app's three settings entry points distinct icons (app preferences, global hiding defaults, per-app override) instead of one shared gear icon
- Replaced the work-profile name badge in the app list with a uid chip on every app row, showing the real per-profile Android UID instead of a resolved profile name
- Show settings icon on all tabs
- Split top hook statistics on the dashboard into Framework (left) and Native (right)

### Fixed
- Fix path existence oracle leaks for VPN interface subdirectories under sysctl/sysfs (/proc/sys/net, /sys/class/net) by hooking path-based syscalls (faccessat, newfstatat, openat, readlinkat)
- check_ipv6_link_local_bruteforce: fix NDP oracle false positive — sendto() synchronous failure (ENODEV for non-existent index) left MSG_ERRQUEUE empty for a non-async reason; now skip silently on send_ret < 0 before sleeping
- check_uid_route_rules_leak: replace naive uid>=10000 detection with span-based analysis (single-UID point rules such as OEM Doze/Work Profile/Clone App are now ignored; only carpet-bombing rules with span>1000 and catch-all markers with uid_end==99999/199999 are flagged as VPN), and fix detection of VPN rules whose uid_range starts below 10000 — rules like [0..app_uid], where the VPN routes from UID 0 up to the target app UID, were previously unfiltered; both sides of the range are now checked: (start >= 10000 || end >= 10000) && end != UINT_MAX
- Fixed UI freezing during diagnostics completion by migrating to LazyColumn and optimizing state recomposition.
- kmod: suppress GSO asymmetry check — zero UDP_SEGMENT in setsockopt to prevent udp_send_skb EIO on tun/WireGuard
- Restore kernel-level hiding of VPN interfaces in /proc/net/dev and /proc/net/if_inet6
- Improved settings screen layout with floating navigation pill, fixed app picker row click to toggle port hiding (P) automatically
- Fixed the predictive-back gesture snapping back to full size before closing, switches in per-app/global hook and port settings not matching their tab's accent color, and the port rule screen rendering under the global/per-app settings header instead of covering it
- Fixed hook cards on the kernel/framework hook settings screens showing an ugly gray shadow ring in light theme by switching them from an elevated card to a flat one
- Shortened the Enable All/Disable All hook buttons and the kernel/framework/port tab labels (now N/F/P, matching the per-app protection chips) so they no longer wrap onto a second line in Russian
- Show Ports as its own category separate from Native in intercept stats, and refresh intercept stats on dashboard pull-to-refresh
- Stopped every hook switch in a category from dimming/flashing while any single one of them was being saved
- Stop resetting hook intercept counters to zero when the statistics retention period is changed in Settings

### Removed
- remove check_arp_timeout_illusion and check_broadcast_blackhole — ARP timeout oracle is conceptually identical to the existing NDP Timeout Oracle in check_ipv6_link_local_bruteforce (both test IFF_NOARP via L2 neighbor resolution absence); broadcast blackhole test (SO_BROADCAST to 255.255.255.255) is covered by the same ARPHRD-based L2 detection
- Removed the 'VPN not active' prompt and its associated logic from the diagnostics and dashboard screens

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
