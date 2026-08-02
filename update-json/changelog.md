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
