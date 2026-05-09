## v0.7.1

### Added
- Expanded Russian app detection: Anixart, ePN Cashback, TNT Premier, Swoo (Кошелёк), Макси, Ростелеком Личный кабинет, Проверка чеков ФНС

### Changed
- Reduced APK size by ~93% (47 MB → 3.37 MB) by enabling R8 minification, resource shrinking, and replacing the com.google.android.material dependency with AndroidX SplashScreen; the splash screen now holds through startup until the Dashboard is ready, removing transient loading spinners on cold launch
- Show profile names (Work, Second Space, ...) next to apps installed in other user profiles, instead of raw user IDs

### Fixed
- Kmod install recommendation no longer falsely pushes users to Zygisk on custom kernels whose `uname -r` lacks the GKI KMI tag (e.g. `android12`, `android13`). The heuristic now matches only the parsed KMI — not the phone's Android OS release, which is an unrelated label — and falls back on kernel series when the KMI is missing: 6.1 / 6.6 / 6.12 each ship a single KMI variant and are still unambiguous; 5.10 and 5.15 each have two candidates, both of which the app now surfaces (primary + alternative), with a dedicated banner when the installed variant fails to load so the user knows to try the other. An active kmod — `/proc/vpnhide_targets` present — also overrides any remaining heuristic-driven warning.
- Polish multi-profile app list: the Show system filter now classifies apps installed only in a secondary profile correctly, and the user-ID suffix no longer appears on every row for users without a secondary profile

## v0.7.0

### Added
- Dashboard now splits issues into Errors (red, block protection) and Warnings (amber, setup is suboptimal but working). Four new warnings: kernel supports kmod but only Zygisk is installed; kmod and Zygisk both active simultaneously (means you have to remember per-app Z-off for banking / payment apps that detect Zygisk); debug logging left on; SELinux in Permissive mode (exposes six detection vectors that VPNHide Next relies on the kernel to block).
- Debug logging toggle in Diagnostics: off by default — VPNHide Next, LSPosed hooks (VpnHide-NC/NI/LP and the package-visibility filter), and zygisk keep logcat near-silent. Start recording and Collect debug log automatically enable verbose logging for the duration of the capture and restore it afterwards, so the toggle is only needed if you want logs emitted continuously outside a capture. Errors always pass through so hook-install failures remain visible.
- Diagnose wrong-variant kmod installs: Dashboard surfaces when the installed kernel module is built for a different GKI variant than your kernel (e.g. android13-5.10 installed on android14-6.1), when your kernel is missing kretprobes, or has no compatible kmod variant at all — and points to the right zip. The same boot-time insmod status is bundled into the Collect debug log zip on the Diagnostics screen for bug reports.
- Expand Russian-apps filter: Pribyvalka 63, RosDomofon, Drivee, Setka, Twinby, GoodLine (4pda user feedback).
- Expand Russian-apps filter: Youla, Delivery Club, SDEK, Russian Post, Dom.ru, ConsultantPlus, etc. — local retailers, pharmacies, food chains and services.
- Multi-profile support: VPNHide Next now targets every instance of a selected app across user profiles (work profile, MIUI Second Space, Private Space, secondary users). Previously hooks only matched the app in your primary profile — a work-profile Ozon would still see the VPN. Package-to-UID resolution at boot + at Save time now uses 'pm list packages -U --user all' and writes every UID to targets, so all profiles are covered automatically without any UI changes. Fixes the work-profile request in issue #15.

### Changed
- Changelog entries now live as per-PR Markdown fragments under changelog.d/ instead of a shared JSON section, and CHANGELOG.md is regenerated only at release time, so concurrent PRs no longer conflict on the changelog.
- Dashboard no longer re-runs all checks (module detection, kprobes, SELinux, target counts, GitHub update check, etc.) on every tab switch. State is loaded once at startup and cached in RAM; the Refresh button in the top bar forces a reload. Update check is cached for 6 hours and re-runs when the app comes back to the foreground after sitting in the background longer than that — no background jobs, no notifications, just reactive to app lifecycle.
- Help text on Protection screens (Tun / Apps / Ports) moved from a hard-to-discover ? icon in the top bar to always-visible collapsible cards at the top of each list. Users who read and understood the hints can collapse them — the state is remembered across app restarts.
- Installed-app list is now loaded once at app start and cached in RAM, so switching between Protection tabs is instant instead of waiting for the icon+label load every time. Added a refresh button in the top bar to force a reload (also re-reads the per-screen target/observer files).
- Diagnostics and Protection tabs now open instantly after the first load. Previously each tab switch re-ran all root-shell probes (module detection, target/observer files, pm lookups, 500ms of check functions), now those results live in process-lifetime caches. Diagnostics specifically: checks run once when VPN is first detected active, then results are fixed for the process — hooks don't change mid-session, so re-running is pointless. When VPN is off, both Dashboard and Diagnostics show a shared "turn on VPN, then Retry" banner. Log-capture tools (debug logging toggle, logcat recorder, debug-zip export) are now always visible on Diagnostics, regardless of VPN state.
- Diagnostics tab opens instantly even on the first time you tap it. The cache that stores the protection-check results now starts running in the background as soon as the app launches, instead of waiting for you to navigate to the tab — by the time you switch from Dashboard to Diagnostics, the results are usually already there.
- Tun help accordion now spells out which layers need a target-app restart after Save: L (LSPosed) and K (kmod) apply immediately, Z (Zygisk) hooks are per-process so any just-toggled target with Z needs a force-stop + reopen to pick up the change.

### Fixed
- App Hiding: marking the same app as both H (Hidden) and O (Observer) caused it to crash on startup — the app would query its own PackageInfo, our system_server hook matched it as an observer and stripped its own package from the result, and the framework bailed. Roles are now mutually exclusive: toggling one clears the other, and existing H+O configs are migrated to O-only on first launch.
- Dashboard now shows a consistent version string for all modules. Kernel-module, Zygisk and Ports module cards used to display the Magisk-style 'vX.Y.Z' from their module.prop, while the LSPosed hook module card showed the Android-style 'X.Y.Z' from the APK's versionName — on the same screen, for the same version number. The 'v' prefix is now stripped at parse time so every card reads 'X.Y.Z' (or 'X.Y.Z-N-gSHA' for dev builds).
- Dev builds of the app no longer trigger a false 'module version mismatch' warning on the Dashboard. The check now strips the git-describe dev suffix (e.g. 0.6.2-14-g1f2205e vs module 0.6.2) before comparing.
- Dev builds of the app now correctly receive "new version available" notifications. The comparison used to bail on the git-describe suffix (0.6.2-14-gSHA) and silently treat it as "no update", so testers running dev APKs never saw release prompts.
- Protection toolbar: the filter icon indicator for "filter is applied" no longer blends into the topbar background on Material You palettes. Active state is now a FilledIconButton (primary / onPrimary pair, guaranteed contrast) instead of a plain icon tinted with primary on top of primaryContainer.

## v0.6.2

### Added
- Full system logcat recording on the Diagnostics screen — Start/Stop an unfiltered capture of all logcat buffers (main/system/crash/events/radio), then Save to a user-picked location via the Storage Access Framework or Share through the system sheet. Useful for submitting diagnostic logs straight from the app without running adb logcat by hand.

### Changed
- Module zips and APK now include git provenance in the version string. Official release builds from a tag stay at the clean version (e.g. 0.6.2); intermediate builds from main or a feature branch show up as 0.6.2-5-gabc1234 (or -dirty) in the Magisk/KSU manager and Android Settings, so debug-log submissions identify the exact commit.

### Fixed
- Target apps (Ozon, Home Assistant, Megafon, Chrome, etc.) no longer hang on infinite load when vpnhide-zygisk is installed. Our recv/recvmsg hooks used to run the netlink-dump filter on every socket regardless of type — on TCP/UDP/Unix the first bytes are arbitrary user data (TLS ciphertext, HTTP body) and occasionally matched RTM_NEWLINK/RTM_NEWADDR by chance, causing the filter to mangle the buffer in-place and corrupt the TLS stream. Gated both hooks on AF_NETLINK so non-netlink traffic passes through untouched.
- Target apps no longer fail Java-level hooks on cold boot. service.sh in the kmod and zygisk modules used to resolve package names to UIDs as soon as pm list packages started responding — but PackageManager responds early with only the system-package snapshot, so user-installed targets (including the vpnhide app itself) got resolved to empty. /data/system/vpnhide_uids.txt was written with at most one UID, the LSPosed hook cached that empty set on its first writeToParcel call, and the Java-level filtering silently no-opped until the next lucky boot. Now service.sh waits until the vpnhide package itself is visible in pm list packages -U, so the full user-package index is ready before resolving.
- Ozon and other apps with root-detection scanning /proc/self/fd no longer hang when vpnhide-zygisk is installed. The module's zygote-side on_load was leaking the module-dir fd, which every forked app process inherited — root-tamper scans detected a descriptor pointing inside /data/adb/modules/ and refused to continue. The fd is now explicitly closed before any app fork.

## v0.6.1

### Added
- Detect gb.sweetlifecl as a Russian-vendor package in the app picker filter, so it shows up under the Russian-only filter alongside other domestic apps.

### Fixed
- Magisk versions before v28 failed to install vpnhide-ports and vpnhide-kmod with an unpack error — restored the META-INF/com/google/android/{update-binary,updater-script} entries the older managers expect.

## v0.6.0

### Added
- App hiding mode in Protection — hide selected apps from selected observer apps at the PackageManager level. Observer apps can no longer list, resolve, or query hidden apps.
- Ports hiding mode plus new vpnhide-ports.zip module — block selected apps from reaching 127.0.0.1 / ::1 ports to hide locally running VPN/proxy daemons (Clash, sing-box, V2Ray, Happ, etc.).
- Ports module integration in the dashboard — shows install state, active rules, observer count, and version mismatch/update warnings.

### Changed
- The old Apps tab is now Protection, split into three modes: Tun, Apps, and Ports.
- Ports rules apply immediately on Save and are restored automatically on boot.
- vpnhide-ports.zip is now included in the release/update pipeline with Magisk/KernelSU update metadata.

### Fixed
- Fixed LinkProperties filtering so VPN routes are stripped more reliably from app-visible network snapshots.
- Fixed SIOCGIFCONF filtering on some Android 12/13 5.10 kernels where the previous hook could succeed but never fire.
- Fixed debug log collection so app logcat entries are captured reliably on devices where logcat via su misses them.
