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
