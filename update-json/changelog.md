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
