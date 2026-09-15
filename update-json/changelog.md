## v2.5.6

### Added
- Diagnostics now probe whether NAT-T keepalive offload can be admitted on Wi-Fi.

### Changed
- Native diagnostics now report more information

### Security
- Block NAT-T keepalive offload requests from protected apps before they can bypass VPN lockdown.

## v2.5.5

### Added
- Add in-app updates for kmods built for Android 17 and kernel 6.18.
- Add Simplified Chinese localization to the app.

### Changed
- Allow the version-mismatch screen to be bypassed once while troubleshooting.
- Make the backend gate repair controls use the full available width.
- Migrate the LSPosed module runtime to Modern Xposed API 102.

### Fixed
- Restore compatible native component versions after an app update.
- Fix an APK crash caused by mismatched native diagnostic bindings and library exports.

## v2.5.4

### Added
- Added a setting to select NoMount instead of SUSFS for VPN filesystem hiding.

### Changed
- Bridge updates can now be installed without updating a compatible built-in kernel.

## v2.5.3

## v2.5.2

### Fixed
- Centralize intercept statistics loading to prevent duplicate screen refreshes
- Respect compatibility and app-first ordering when offering component updates; continue metadata generation when individual release artifacts are unavailable
- Offer a kmod update when the installed backend is incompatible with the app
- Reapply the current policy after an installed app is updated, even when its UID is unchanged.
