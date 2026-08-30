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

<p align="center"><strong><a href="README.md">Russian version</a> · <a href="README.zh-CN.md">简体中文</a></strong></p>

## Installing the kernel backend

Full protection requires one of the two kernel backend options. Do not install
kmod on top of a kernel with kpatch; choose the option that matches your device.

### kmod (GKI2)

1. Check the Android version and kernel version on your device.
2. From the [latest release](https://github.com/soranerai/vpnhide_next/releases/latest),
   download the ZIP matching your Android branch and kernel KMI. For example:
   `vpnhide-kmod-android14-6.1.zip`.
3. Install the ZIP through KernelSU, Magisk, or another root manager.
4. Reboot the device. The module must be active after reboot.

kmod supports GKI2 only. The available variants are listed in the releases and
cover Android 12–17 with kernels 5.10–6.18. If there is no matching variant for
your kernel, check whether you can install a matching pre-built GKI2 kernel with
kpatch or apply a patch manually.

### kpatch + bridge

This option is intended for pre-built GKI2 kernels with kpatch already applied.
Older kernels require manual patching of the specific kernel; instructions are
available in the private backend repository.

1. Install a kernel with kpatch applied. Pre-built kernels for standard GKI2 are
   published in the [GKI_KernelSU_SUSFS](https://github.com/soranerai/GKI_KernelSU_SUSFS)
   repository; for manual patching of a specific kernel, follow the instructions
   in the private backend repository [vpnhide_next_private](https://github.com/soranerai/vpnhide_next_private).
2. From the [latest release](https://github.com/soranerai/vpnhide_next/releases/latest),
   download `vpnhide-bridge.zip` and install it through your root manager.
3. Reboot the device. The bridge must match the app and built-in kernel versions.

After installing the kernel backend, install the VPNHide Next APK, enable it for
**System Framework** in LSPosed, and reboot again if you have not already done so.
The app will show which backend is active and whether the bridge loaded in Diagnostics.

> [!WARNING]
> **Requires kernel-level and system framework-level integration**
> Stable operation across all devices, ROMs, and kernel versions is not guaranteed. The software is provided "AS IS" under the MIT License. The author assumes no liability for any device issues.

---

### Architecture and Integration Methods

The project implements a two-component hiding model that combines low-level system call interception in the kernel with system framework response modification:

1. **Kernel Module (LKM or Built-in)**:
   * **LKM (Loadable Kernel Module)**: Distributed as a loadable module for Android GKI kernels (Android 12–17, versions 5.10–6.18). Android 17 / kernel 6.18 is included in release publishing and automatic updates. Uses `kretprobes` to intercept network-related system calls and filesystem operations.
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
* **Port Access Monitoring**: Ability to track and monitor which network ports targeted applications are requesting access to.
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

---

### Many Thanks

* [KernelSU](https://github.com/tiann/KernelSU) / [KernelSU-Next](https://github.com/rifsxd/KernelSU-Next) — for the excellent root solution.
* [wildkernels](https://github.com/wildkernels) — for the base of the built-in mode.
* [okhsunrog/vpnhide](https://github.com/okhsunrog/vpnhide) — for the base and inspiration of this project.
* [LSPosed](https://github.com/LSPosed/LSPosed) — for the powerful system framework hooking mechanisms.
* [SUSFS](https://gitlab.com/ephxius/susfs4ksu) — for the file/path hiding concept in kernel-space.
