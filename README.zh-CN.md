<p align="center">
  <img src="assets/logo.png" width="200" alt="VPNHide Next" />
</p>

<h1 align="center">VPNHide Next</h1>

<p align="center">用于在 Android 设备上向指定应用隐藏活动 VPN 连接的系统级解决方案。</p>

<p align="center">
  <a href="https://github.com/soranerai/vpnhide_next/releases/latest"><img src="https://img.shields.io/github/v/release/soranerai/vpnhide_next" alt="Release"></a>
  <a href="https://github.com/soranerai/vpnhide_next/releases"><img src="https://img.shields.io/github/downloads/soranerai/vpnhide_next/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
</p>

<p align="center"><strong><a href="README.md">Русский</a> · <a href="README.en.md">English</a></strong></p>

## 安装 kernel backend

完整保护需要以下两种 kernel backend 方案之一。不要在已使用 kpatch 的
kernel 上安装 kmod，请根据设备选择合适的方案。

### kmod (GKI2)

1. 查看设备的 Android 版本和 kernel 版本。
2. 从[最新 release](https://github.com/soranerai/vpnhide_next/releases/latest)
   下载与 Android 分支和 kernel KMI 匹配的 ZIP。例如：
   `vpnhide-kmod-android14-6.1.zip`。
3. 通过 KernelSU、Magisk 或其他 root manager 安装 ZIP。
4. 重启设备。重启后模块必须处于 active 状态。

kmod 仅支持 GKI2。release 中列出了可用变体，覆盖 Android 12–17 和
5.10–6.18 kernel。如果没有适用于你的 kernel 的变体，请确认是否可以安装
   匹配的预构建 GKI2 kernel（已应用 kpatch），或手动应用 patch。

### kpatch + bridge

此方案适用于已经应用 kpatch 的预构建 GKI2 kernel。较旧的 kernel 需要对
具体 kernel 进行手动 patch；相关说明位于 private backend repository。

1. 安装已经应用 kpatch 的 kernel。标准 GKI2 的预构建 kernel 发布在
   [GKI_KernelSU_SUSFS](https://github.com/soranerai/GKI_KernelSU_SUSFS)
   repository 中；如需对具体 kernel 手动 patch，请按照 private backend
   repository [vpnhide_next_private](https://github.com/soranerai/vpnhide_next_private)
   中的说明操作。
2. 从[最新 release](https://github.com/soranerai/vpnhide_next/releases/latest)
   下载 `vpnhide-bridge.zip`，并通过 root manager 安装。
3. 重启设备。bridge 必须与 app 和 built-in kernel 的版本匹配。

安装 kernel backend 后，安装 VPNHide Next APK，在 LSPosed 中将其启用到
**System Framework**，如果之前尚未重启则再次重启设备。应用的 Diagnostics
页面会显示 active backend 以及 bridge 是否已加载。

> [!WARNING]
> **需要 kernel-level 和 system framework-level integration**
> 无法保证在所有设备、ROM 和 kernel 版本上稳定运行。本软件依据 MIT License 按“AS IS”提供。作者不对任何设备问题承担责任。

---

### 架构与 integration methods

项目采用双组件隐藏模型，将 kernel 中的低级 system call interception 与
system framework response modification 结合起来：

1. **Kernel Module（LKM 或 Built-in）**：
   * **LKM（Loadable Kernel Module）**：作为可加载 kernel module 提供，适用于 Android GKI kernel（Android 12–17，5.10–6.18）。Android 17 / kernel 6.18 已加入 release publishing 和 automatic updates。使用 `kretprobes` interception network-related system calls 和 filesystem operations。
   * **Built-in Mode**：在构建时将 hiding logic 直接集成到 monolithic kernel source 中。无需 runtime module loading，并提供 zero call overhead、对 syscall timing attacks 的绝对抵抗和最高稳定性。预构建版本可在以下 repository 中获取：
     * **Standard GKI2**：[GKI_KernelSU_SUSFS](https://github.com/soranerai/GKI_KernelSU_SUSFS)
2. **LSPosed Module**：
   * 在 `system_server` process 中 interception Binder IPC calls（具体为 `NetworkCapabilities`、`NetworkInfo` 和 `LinkProperties` 的 Parcel serialization）。这样无需向 target app process 注入代码，即可避免通过 Java API 泄露 VPN information。

---

### 主要功能

* **100% stealth（built-in）**：hiding logic 直接编译进 kernel，移除 external module 和 kretprobe hooks。保护机制不会受到 syscall timing attacks 影响，也不会被 user-space analysis 或 anti-tamper SDK 检测到。
* **Unlimited target applications**：filtering mechanism 支持无限数量的 target application。effective UID list 在 daemon 侧动态计算，避免 system buffer overflow。
* **List modes（Blacklist / Whitelist）**：
  * *Blacklist Mode*：只向明确选择的 application 隐藏 VPN interface 和 socket options。
  * *Whitelist Mode*：向所有已安装 application 隐藏 VPN，仅对 exclusion list 中明确选择的 application 保留可见性。
* **Dynamic port rules**：通过 `security_socket_connect` / `security_socket_bind` hooks，在 kernel level block 或 redirect 到本地 VPN daemon port 的 connection。port rules 动态管理，不依赖 iptables rules 或 ProcFS。
* **Port access monitoring**：跟踪和监控 target application 请求访问的 network port。
* **Dynamic hook configuration**：通过 `/dev/vpnhide_ctrl` control device 动态切换 hiding parameters 和 protection levels，无需 reboot 即可生效。
* **Automatic controller hiding**：VPN client application 和 VPNHide Next manager 会在 runtime 自动对 target application 隐藏。
* **Work Profile support**：在 primary user 与 isolated Android work profile（Multi-user/Work Profile）之间完全隔离 hiding logic 和 target list。

---

### Protection levels

可通过 dashboard 动态切换 protection level，以配置 active kernel hooks：

| Level | Description | Covered detection vectors |
|---|---|---|
| **Minimum（Min）** | Basic network isolation | Interface enumeration filtering（`ioctl`、netlink）、port blocking，以及隐藏 `/proc/net/route` 中的 routes。 |
| **Average（Avg）** | Socket 和 network parameter isolation | **Min** 的全部功能 + interception `getsockopt`/`setsockopt`（MTU、MSS、`TCP_INFO`、`SO_BINDTODEVICE`、`SO_TIMESTAMPING` spoofing），防止 socket mark 泄露（`SO_MARK`）。 |
| **Maximum（Max）** | Comprehensive system isolation | **Avg** 的全部功能 + 隐藏 `/proc/net/{dev,if_inet6,fib_trie}` 和 `/sys/class/net` 中的 files、清零 eBPF traffic statistics、UDP timing attack protection、IPv6 link-local bruteforce protection，以及隐藏 VPN qdisc。 |

---

### Diagnostics 与 monitoring

应用内置 diagnostic module，执行 **44 项 automated checks**（37 项 native-level 和 7 项 Java-level checks），用于验证所有已知 detection vector 的 hiding completeness。

intercept statistics 由 daemon 累积到 in-memory ring buffer，并可通过 abstract socket `vpnhide.stats.v1` 实时查询。

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

### 致谢

* [KernelSU](https://github.com/tiann/KernelSU) / [KernelSU-Next](https://github.com/rifsxd/KernelSU-Next) — excellent root solution。
* [wildkernels](https://github.com/wildkernels) — built-in mode 的基础。
* [okhsunrog/vpnhide](https://github.com/okhsunrog/vpnhide) — 本项目的基础和灵感来源。
* [LSPosed](https://github.com/LSPosed/LSPosed) — 强大的 system framework hooking mechanisms。
* [SUSFS](https://gitlab.com/ephxius/susfs4ksu) — kernel-space 中 file/path hiding concept。
