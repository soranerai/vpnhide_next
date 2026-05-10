# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## v1.1.0

### Added
- **Granular Port Hiding**: Block specific ports and protocols (TCP/UDP) for targeted apps at the kernel level.
- **Port Rules Persistence**: Configuration is now saved to `/data/adb/vpnhide_ports/rules.txt` and restored at boot.
- **Improved UI**: Interactive port rules editor with protocol toggles and range support.

### Fixed
- Improved kernel memory stability by switching to `kvmalloc` for large rule sets.
- Fixed potential RCU race condition in socket connection filtering.

## v1.0.0

### Added
- Deep redesign and optimization: Completely reworked interface (skeleton, async loading) and optimized code
- Flexible sorting: Added the ability to sort applications properly
- Hiding anonymous TUN routes: Exclusion of TUN from route requests
- Kernel-level bind bypass: Ability to route packets directly, bypassing any application binds at the kernel level
- Maximum stealth: Complete removal of /proc/ files accessible to all applications, eliminating module detection via the file system
