<p align="center">
  <img src="assets/logo.png" width="200" alt="VPNHide Next" />
</p>

<h1 align="center">VPNHide Next</h1>

<p align="center">Hide an active Android VPN connection from selected apps.</p>

<p align="center">
  <a href="https://github.com/soranerai/vpnhide_next/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/soranerai/vpnhide_next/ci.yml?label=CI" alt="CI"></a>
  <a href="https://github.com/soranerai/vpnhide_next/releases/latest"><img src="https://img.shields.io/github/v/release/soranerai/vpnhide_next" alt="Release"></a>
  <a href="https://github.com/soranerai/vpnhide_next/releases"><img src="https://img.shields.io/github/downloads/soranerai/vpnhide_next/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
</p>

<p align="center"><strong><a href="README.md">Русская версия</a></strong></p>

**vpnhide** is a tool to hide VPN usage from Android applications. It makes the VPN connection invisible even to services that actively try to detect it (such as banking apps, streaming platforms, or region-restricted services).

### Architecture
*   **`kmod`** — kernel module (recommended), operating outside the application process context. Requirements: GKI + ARM64-v8a.
*   **`lsposed`** — Binder transaction filtering in `system_server`. Optional.
*   **`zygisk`** — native hooks for devices without kernel module support. Identical to the original repository.
*   **`portshide`** — blocking access to localhost to prevent proxy daemon detection. Optional.

### Installation
1.  Install `vpnhide.apk` and enable the module in LSPosed (scope: System Framework).
2.  Reboot your device.
3.  Install the recommended native module (`kmod` or `zygisk`) via the app.
4.  Select target apps in the "Protection" tab and save your settings.

### Screenshots
| Dashboard | App Selection | Sorting | Diagnostics |
|:-:|:-:|:-:|:-:|
| <img src="assets/screenshots/Dashboard.jpg" width="200"> | <img src="assets/screenshots/AppSelector.jpg" width="200"> | <img src="assets/screenshots/SortMenu.jpg" width="200"> | <img src="assets/screenshots/Diagnostics.jpg" width="200"> |
| **Bulk Ports Rules** | **Local Ports Rules** | **Rules Validation** | **FAQ** |
| <img src="assets/screenshots/Bulk%20edit%20rules.jpg" width="200"> | <img src="assets/screenshots/Local%20ports%20edit.jpg" width="200"> | <img src="assets/screenshots/Duplicate%20and%20redutant%20protection.jpg" width="200"> | <img src="assets/screenshots/FAQ.jpg" width="200"> |

---
### Project Information
This is a fork of the [okhsunrog/vpnhide](https://github.com/okhsunrog/vpnhide/) project. This branch was detached from the upstream due to significant changes.

**Main differences from the original:**
*   **Dropped support for legacy architectures**: Only arm64 is supported.
*   **Deep redesign and optimization**: Completely overhauled interface (skeleton, async loading) and significantly optimized code.
*   **Flexible sorting**: Added the ability for proper application sorting.
*   **Anonymous TUN route hiding**: Exclusion of TUN interfaces from route requests.
*   **Kernel-Level Bind Bypass**: Ability to deploy packages directly bypassing any application binds at the kernel level.
*   **Fully reworked port blocking**: Rule-based port access blocking mechanism. The logic has been moved from iptables to the kernel.
*   **Database-driven architecture**: Rules are mirrored and stored in the application's database.
*   **Maximum Stealth**: Complete removal of `/proc/` files accessible to all applications, eliminating module detection through the file system.
