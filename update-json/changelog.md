## v2.5.3

## v2.5.2

### Fixed
- Centralize intercept statistics loading to prevent duplicate screen refreshes
- Respect compatibility and app-first ordering when offering component updates; continue metadata generation when individual release artifacts are unavailable
- Offer a kmod update when the installed backend is incompatible with the app
- Reapply the current policy after an installed app is updated, even when its UID is unchanged.

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
