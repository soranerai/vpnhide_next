<p align="center">
  <img src="assets/logo.png" width="200" alt="VPNHide Next" />
</p>

<h1 align="center">VPNHide Next</h1>

<p align="center">A system-level solution designed to hide active VPN connections on Android devices from selected applications.</p>

<p align="center">
  <a href="https://github.com/soranerai/vpnhide_next/releases/latest"><img src="https://img.shields.io/github/v/release/soranerai/vpnhide_next" alt="Release"></a>
  <a href="https://github.com/soranerai/vpnhide_next/releases"><img src="https://img.shields.io/github/downloads/soranerai/vpnhide_next/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
</p>

<p align="center"><strong><a href="README.md">Russian version</a></strong></p>

> [!WARNING]
> **Requires kernel-level and system framework-level integration**
> Stable operation across all devices, ROMs, and kernel versions is not guaranteed. The software is provided "AS IS" under the MIT License. The author assumes no liability for any device issues.

---

### Architecture and Integration Methods

The project implements a two-component hiding model that combines low-level system call interception in the kernel with system framework response modification:

1. **Kernel Module (LKM or Built-in)**:
   * **LKM (Loadable Kernel Module)**: Distributed as a loadable module for Android GKI kernels (Android 12–16, versions 5.10–6.12). Uses `kretprobes` to intercept network-related system calls and filesystem operations.
   * **Built-in Mode**: Direct integration of the hiding logic into the monolithic kernel source at build time. Provides zero call overhead, absolute resistance to syscall timing attacks, and maximum stability without depending on runtime module loading. Pre-built builds are available in the following repositories:
     * **Standard GKI2**: [GKI_KernelSU_SUSFS](https://github.com/soranerai/GKI_KernelSU_SUSFS)
2. **LSPosed Module**:
   * Intercepts Binder IPC calls inside the `system_server` process (specifically, Parcel serialization for `NetworkCapabilities`, `NetworkInfo`, and `LinkProperties`). This prevents VPN info leaks via Java APIs without injecting code into the target app processes.

---

### Key Features

* **100% Stealth (built-in)**: The hiding logic is compiled directly into the kernel, eliminating the use of external modules and kretprobe hooks. This makes the protection mechanisms completely invulnerable to syscall timing attacks and undetectable by any user-space analysis or anti-tamper SDKs.
* **Unlimited Target Applications**: The filtering mechanism supports an unlimited number of target applications. The effective UID list is computed dynamically on the daemon side, preventing system buffer overflows.
* **List Modes (Blacklist / Whitelist)**:
  * *Blacklist Mode*: Hides VPN interfaces and socket options only from explicitly selected applications.
  * *Whitelist Mode*: Hides VPN from all installed applications except those explicitly selected in the exclusion list.
* **Dynamic Port Rules**: Kernel-level blocking and redirection of connections to local VPN daemon ports using the `security_socket_connect` / `security_socket_bind` hooks. Port rules are managed dynamically without relying on iptables rules or ProcFS.
* **Dynamic Hook Configuration**: Hiding parameters and protection levels can be toggled on the fly via the `/dev/vpnhide_ctrl` control device. Changes are applied immediately without rebooting.
* **Automatic Controller Hiding**: The VPN client applications and the VPNHide Next manager are automatically hidden from target applications at runtime.
* **Work Profile Support**: Full separation of hiding logic and target lists between the primary user and isolated Android work profiles (Multi-user/Work Profile).

---

### Protection Levels

Protection levels can be toggled dynamically via the dashboard, configuring the active set of kernel hooks:

| Level | Description | Covered Detection Vectors |
|---|---|---|
| **Minimum (Min)** | Basic network isolation | Interface enumeration filtering (`ioctl`, netlink), port blocking, and hiding routes in `/proc/net/route`. |
| **Average (Avg)** | Socket and network parameter isolation | All features of **Min** + `getsockopt`/`setsockopt` interception (MTU, MSS, `TCP_INFO`, `SO_BINDTODEVICE`, `SO_TIMESTAMPING` spoofing), preventing socket mark leakage (`SO_MARK`). |
| **Maximum (Max)** | Comprehensive system isolation | All features of **Avg** + hiding files in `/proc/net/{dev,if_inet6,fib_trie}` and `/sys/class/net`, zeroing eBPF traffic statistics, UDP timing attack protection, IPv6 link-local bruteforce protection, and hiding VPN qdisc. |

---

### Diagnostics and Monitoring

The application features a built-in diagnostic module performing **44 automated checks** (37 native-level and 7 Java-level checks) to verify hiding completeness against all known detection vectors.

Intercept statistics are accumulated by the daemon in an in-memory ring buffer and can be queried in real time via the `vpnhide.stats.v1` abstract socket.

---

### Screenshots

<div align="center">

| Dashboard | App List | Statistics |
|:-:|:-:|:-:|
| <img src="assets/screenshots/dushboard.jpg" width="200"> | <img src="assets/screenshots/apps_picker.jpg" width="200"> | <img src="assets/screenshots/statistics_screen.jpg" width="200"> |
| **Hook Settings** | **App Settings** | **Diagnostics** |
| <img src="assets/screenshots/hook_settings.jpg" width="200"> | <img src="assets/screenshots/app_settings.jpg" width="200"> | <img src="assets/screenshots/diagnostics_screen.jpg" width="200"> |

</div>