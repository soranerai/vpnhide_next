<p align="center">
  <img src="assets/logo.png" width="200" alt="VPNHide Next" />
</p>

<h1 align="center">VPNHide Next</h1>

<p align="center">Hides an active VPN connection on Android from selected applications.</p>

<p align="center">
  <a href="https://github.com/soranerai/vpnhide_next/releases/latest"><img src="https://img.shields.io/github/v/release/soranerai/vpnhide_next" alt="Release"></a>
  <a href="https://github.com/soranerai/vpnhide_next/releases"><img src="https://img.shields.io/github/downloads/soranerai/vpnhide_next/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
</p>

<p align="center"><strong><a href="README.md">Russian version</a></strong></p>

> [!WARNING]
> **This fork uses extremely aggressive kernel-level and framework-level hiding methods.**
> Stable operation on absolutely all devices, firmware, and kernel versions **is not guaranteed and cannot be guaranteed**.
> In accordance with the MIT License, the software is provided "AS IS", without warranty of any kind. The author is not responsible for any malfunctions, bootloops, or kernel panics.

---
### Project Information
This is a fork of the [okhsunrog/vpnhide](https://github.com/okhsunrog/vpnhide/) project. Like the original, it hides an active VPN from selected apps on three layers — LSPosed hooks in `system_server`, a native backend — LKM, and optional port blocking — but it was separated from upstream due to significant changes and a different philosophy.
The philosophy of this project is to block ALL direct and indirect vectors at the root, not just the obvious ones.

**Key differences from the original (in brief):**
* **Kernel-level port blocking**: instead of a separate iptables module, loopback connections to VPN daemon control ports are blocked by the `security_socket_connect` kernel hook — no iptables rules, no ProcFS.
* **New protection tiers that don't exist upstream at all**: see "Protection Levels" below — MTU/MSS/TCP_INFO spoofing, GSO/PMTU probe resistance, zeroed eBPF traffic stats, qdisc hiding, timing-attack and IPv6 link-local bruteforce resistance.
* **Different data exchange architecture**: complete abandonment of ProcFS (files in `/proc/`) in favor of the `/dev/vpnhide_ctrl` misc device.
* **Work profile support**: full separation of applications and work profiles.
* **Single source of truth**: a single JSON file on disk for the entire configuration; at runtime, all data passes through the kernel.
* **Automatic app hiding**: automatic hiding of VPN applications from LSPosed targets at runtime. No need to resave targets.

### Protection Levels
The level is picked on the dashboard and toggles the active set of kernel hooks on the fly — a trade-off between hiding completeness and performance.

| Level | Coverage |
|---|---|
| **Min** | Interface/address enumeration (`getifaddrs`, `ioctl`, netlink, kernel routing tables) + VPN port blocking on `bind()`/`connect()`. Not covered: socket options (MTU/MSS/TCP_INFO), GSO/PMTU probes, eBPF traffic stats, `/sys/class/net`. |
| **Avg** | Everything in "Min" + `setsockopt`/`getsockopt` interception: MTU, MSS, `TCP_INFO`, `SO_BINDTODEVICE`, and `SO_TIMESTAMPING` are spoofed to match the physical interface, `SO_MARK` no longer leaks, GSO/PMTU probes are neutralized. |
| **Max** | Everything in "Avg" + `/proc/net/{dev,if_inet6,fib_trie}` and `/sys/class/net` hidden from the filesystem, eBPF traffic stats zeroed, UDP protected against timing attacks, IPv6 link-local bruteforce blocked, VPN qdisc hidden. Covers all known direct and indirect detection vectors. |

### How far this fork has gone beyond the original
Upstream closes roughly 25 detection vectors (native syscalls, netlink, `/proc`, Java connectivity APIs). This fork checks the same base vectors (the "Classic" diagnostic tier) and adds two further tiers — **Advanced** and **Extreme** — for a total of **44 automated diagnostic checks** (37 native + 7 Java-level) in the built-in diagnostics screen. About 20 of those (MSS/PMTU/TCP_INFO spoofing, zeroed eBPF traffic stats, qdisc hiding, UDP timing-attack resistance, IPv6 link-local bruteforce resistance, the `RTM_GETLINK` trim oracle, and others) don't exist upstream at all — vectors the original doesn't even attempt to close.

### Screenshots
<div align="center">

| Dashboard | App List | Statistics |
|:-:|:-:|:-:|
| <img src="assets/screenshots/dushboard.jpg" width="200"> | <img src="assets/screenshots/apps_picker.jpg" width="200"> | <img src="assets/screenshots/statistics_screen.jpg" width="200"> |
| **Hook Settings** | **App Settings** | **Diagnostics** |
| <img src="assets/screenshots/hook_settings.jpg" width="200"> | <img src="assets/screenshots/app_settings.jpg" width="200"> | <img src="assets/screenshots/diagnostics_screen.jpg" width="200"> |

</div>