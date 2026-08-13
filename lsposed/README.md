# vpnhide_next -- LSPosed module + policy manager app

Hooks `writeToParcel()` in `system_server` to strip VPN data before Binder serialization reaches target apps. Part of [vpnhide_next](../README.md).

The APK also serves as the policy management UI for the entire vpnhide_next
project. It writes one atomic policy file for the kernel and Java layers.

Zero presence in the target app's process -- only "System Framework" is needed in the LSPosed scope.

## What it hooks

`writeToParcel()` on three classes inside `system_server`:

| Class | Effect |
|---|---|
| `NetworkCapabilities` | VPN transport and capability flags stripped before serialization. Covers `hasTransport(VPN)`, `getAllNetworks()` + VPN scan, `getTransportInfo()`. |
| `NetworkInfo` | VPN type rewritten to WIFI before serialization |
| `LinkProperties` | VPN interface name and routes stripped before serialization |

Uses a ThreadLocal save/restore pattern so the original values are preserved for non-target callers.

### Per-UID filtering

Filtering is controlled by `Binder.getCallingUid()` -- only apps whose UID appears in the target list see the filtered view. System services, VPN clients, and everything else see real data.

### Target management

Effective target UIDs are resolved by `vpnhide-ctl` from the declarative JSON
policy and applied atomically through the kernel policy API. The app does not
write a shared UID file.

## Target picker app

The APK includes a Compose UI for managing target apps across all vpnhide modules:

- Lists all installed apps with icons, names, and package names
- Text search filter
- System apps toggle in both list modes; allowlist shows them selected by
  default and requires a one-time safety confirmation before targeting them
- Save atomically updates
  `/data/user_de/0/dev.soranerai.vpnhidenext/files/vpnhide_config.json`.
- The root daemon watches this file and applies the complete policy without
  per-component target setters.

Works on KernelSU, Magisk, and any other root solution.

## Install

1. Build the APK (`./gradlew assembleDebug`).
2. Install: `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. Open LSPosed/Vector manager, go to Modules, enable **VPNHide Next**.
4. Add **"System Framework"** to the module's scope. No other apps should be in scope.
5. Reboot.
6. Open the VPNHide Next app to manage target apps.

## Combined use with kmod

For apps with aggressive anti-tamper SDKs, full VPN hiding requires covering both native and Java API paths without any hooks in the target app's process:

- **[kmod](../kmod/)** covers native: `ioctl`, `getifaddrs` (netlink), `/proc/net/route`.
- **This module** covers Java APIs: `NetworkCapabilities`, `NetworkInfo`, `LinkProperties` via `writeToParcel()` in `system_server`.

Together they provide complete VPN hiding with zero footprint in the target process.

## Debugging

```bash
adb logcat | grep VpnHide
```

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 17 or later. Output: `app/build/outputs/apk/debug/app-debug.apk`.

The diagnostic Rust library is built in the private `vpnhide_next_private`
repository. CI downloads its prebuilt `libvpnhide_checks.so` into the APK's
`jniLibs/`; local builds can use `../scripts/build-app.sh` with the private
checkout available. The committed UniFFI Kotlin bindings stay in this repo.

## License

MIT. See [LICENSE](../LICENSE).
