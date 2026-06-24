<p align="center">
  <img src="assets/logo.png" width="200" alt="VPNHide Next" />
</p>

<h1 align="center">VPNHide Next</h1>

<p align="center">Hides an active VPN connection on Android from selected applications.</p>

<p align="center">
  <a href="https://github.com/soranerai/vpnhide_next/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/soranerai/vpnhide_next/ci.yml?label=CI" alt="CI"></a>
  <a href="https://github.com/soranerai/vpnhide_next/releases/latest"><img src="https://img.shields.io/github/v/release/soranerai/vpnhide_next" alt="Release"></a>
  <a href="https://github.com/soranerai/vpnhide_next/releases"><img src="https://img.shields.io/github/downloads/soranerai/vpnhide_next/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
</p>

<p align="center"><strong><a href="README.en.md">English version</a></strong></p>

> [!WARNING]
> **This fork uses extremely aggressive kernel-level and framework-level hiding methods.**
> Stable operation on absolutely all devices, firmware, and kernel versions **is not guaranteed and cannot be guaranteed**.
> In accordance with the MIT License, the software is provided "AS IS", without warranty of any kind. The author is not responsible for any malfunctions, bootloops, or kernel panics.

---
### Project Information
This is a fork of the [okhsunrog/vpnhide](https://github.com/okhsunrog/vpnhide/) project. This project was separated from the upstream due to significant changes and a different philosophy.
The philosophy of this project is to block ALL direct and indirect vectors at the root.

**Key differences from the original (in brief):**
* **Kernel-level port blocking**: Loopback connection blocking has been moved from iptables to the `security_socket_connect` kernel hook.
* **16+ new advanced protection vectors**: Hides everything that can possibly be hidden.
* **Different data exchange architecture**: Complete abandonment of ProcFS (files in `/proc/`) in favor of the `/dev/vpnhide_ctrl` misc device.
* **Work profile support**: Full separation of applications and work profiles.
* **Single source of truth**: A single JSON file on disk for the entire configuration. At runtime, all data passes through the kernel.
* **Monitoring and interception statistics (Native & Framework)**: Real-time collection of detailed blocking and spoofing statistics.
* **Automatic app hiding**: Automatic hiding of VPN applications from LSPosed targets.

### Screenshots
| Dashboard | App List | Sorting | Diagnostics |
|:-:|:-:|:-:|:-:|
| <img src="assets/screenshots/Dashboard.jpg" width="200"> | <img src="assets/screenshots/AppSelector.jpg" width="200"> | <img src="assets/screenshots/SortMenu.jpg" width="200"> | <img src="assets/screenshots/Diagnostics.jpg" width="200"> |
| **Bulk port rules** | **Local port rules** | **Port rule validation** | **FAQ** |
| <img src="assets/screenshots/Bulk%20edit%20rules.jpg" width="200"> | <img src="assets/screenshots/Local%20ports%20edit.jpg" width="200"> | <img src="assets/screenshots/Duplicate%20and%20redutant%20protection.jpg" width="200"> | <img src="assets/screenshots/FAQ.jpg" width="200"> |
| **Custom Tun prefixes** | **Hook isolation** | | |
| <img src="assets/screenshots/Custom%20tun%20interfaces.jpg" width="200"> | <img src="assets/screenshots/Hook%20isolation.jpg" width="200"> | | |