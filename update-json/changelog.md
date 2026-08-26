## v2.5.1

### Added
- Add a blocking animated backend setup screen with kmod and built-in repair flows

### Changed
- Separate bridge installation from active backend diagnostics
- The update-check setting now disables all app update checks, including foreground checks.

### Fixed
- Disabling the protection health check now also stops an already queued worker from running.
- Settings now restore the previous value and show an error when a setting cannot be persisted.
- Treat vpnhide_ctrl presence as active and repair only the missing bridge
- Prevent the protection picker from reverting newly saved targets after an asynchronous refresh
- Use an import icon for the backup restore file picker

## v2.5.0

### Changed
- LSPosed now hides all active VPN interfaces reported by the daemon
- Optimize LSPosed hook callbacks by reusing effective UID context
- Recognize ap, Tailscale, HE IPv6, ZeroTier, and short Tailscale interfaces as VPN tunnels
- Improved port rule editing with strict range validation and inline guidance
- Disabled CONFIG_USER_NS in built-in build to eliminate indirect kernel modification indicator
- Optimized eBPF hooks (-3.5% wall -3.3% CPU)
- Optimized readdir hooks (-3.5% wall -3% CPU)
- Other minor optimizations

### Fixed
- Show built-in bridge updates independently when the embedded kernel is already current
- Avoid exposing incomplete mobile LinkProperties during network handover by reusing the last complete snapshot for the same interface.

## v2.4.0

### Added
- Add six-hour interception statistics with a dedicated per-app port history screen
- The app can now download, verify, back up, and install paired VPNHide bridge and built-in kernel updates, with Normal/Bypass image selection and reboot controls.

### Changed
- System apps can now be selected as protection targets with safe allowlist defaults, clear automatic-selection labels, manual exception counts, an always-visible expressive name/package/UID search bar with embedded actions, compact locked-UID guidance, and a cooler green/blue theme.

## v2.3.0

### Added
- The app can now securely download, verify, and install kmod updates through KernelSU, APatch, or Magisk.

### Changed
- Synchronize Framework intercept statistics with the daemon's in-memory history ring

### Fixed
- all: Added exclusion of dummy0 from interfaces for spoofing
- built-in: Fixed wifi/bluetooth disruption on some devices
- app: Changing between Hide and Show modes now fully clears app targets, per-app and global hook overrides, and local and global port rules on save.

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
