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
