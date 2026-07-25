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
