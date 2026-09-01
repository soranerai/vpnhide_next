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

## 安装内核后端

完整保护需要以下两种内核后端方案之一。请勿在已使用 kpatch 的内核上安装
kmod，请根据设备选择合适的方案。

### kmod (GKI2)

1. 查看设备的 Android 版本和内核版本。
2. 从[最新版本](https://github.com/soranerai/vpnhide_next/releases/latest)
   下载与 Android 分支和内核 KMI 匹配的 ZIP。例如：
   `vpnhide-kmod-android14-6.1.zip`。
3. 通过 KernelSU、Magisk 或其他 Root 管理器安装 ZIP。
4. 重启设备。重启后模块必须处于激活状态。

kmod 仅支持 GKI2。发布页列出了可用变体，覆盖 Android 12–17 和
5.10–6.18 内核。如果没有适用于你的内核的变体，请确认是否可以安装
匹配的预构建 GKI2 内核（已应用 kpatch），或手动应用补丁。

### kpatch + bridge

此方案适用于已应用 kpatch 的预构建 GKI2 内核。较旧的内核需要针对具体
内核手动打补丁；相关说明位于私有后端仓库。

1. 安装已应用 kpatch 的内核。标准 GKI2 的预构建内核发布在
   [GKI_KernelSU_SUSFS](https://github.com/soranerai/GKI_KernelSU_SUSFS)
   仓库中；如需为特定内核手动打补丁，请按私有后端仓库
   [vpnhide_next_private](https://github.com/soranerai/vpnhide_next_private)
   中的说明操作。
2. 从[最新 release](https://github.com/soranerai/vpnhide_next/releases/latest)
   下载 `vpnhide-bridge.zip`，并通过 Root 管理器安装。
3. 重启设备。桥接组件必须与应用和内置内核的版本匹配。

安装内核后端后，安装 VPNHide Next APK，并在 LSPosed 中为 **System Framework**
启用它。如果此前尚未重启，请再重启设备。应用的“诊断”页面会显示已激活的
后端，以及桥接组件是否已加载。

> [!WARNING]
> **需要内核层和系统框架层集成**
> 无法保证在所有设备、ROM 和内核版本上稳定运行。本软件依据 MIT 许可证按“原样”提供。作者不对任何设备问题承担责任。

---

### 架构与集成方式

项目采用双组件隐藏模型，将内核中的低级系统调用拦截与系统框架的响应修改
结合起来：

1. **内核模块（LKM 或内置）**：
   * **LKM（可加载内核模块）**：适用于 Android GKI 内核（Android 12–17，5.10–6.18）。Android 17 / 内核 6.18 已纳入发布和自动更新。该模块使用 `kretprobes` 拦截网络相关系统调用和文件系统操作。
   * **内置模式**：构建时将隐藏逻辑直接集成到单体内核源代码中。无需在运行时加载模块，调用开销为零，可抵御系统调用计时攻击，并具有最高稳定性。预构建版本见：
     * **标准 GKI2**：[GKI_KernelSU_SUSFS](https://github.com/soranerai/GKI_KernelSU_SUSFS)
2. **LSPosed 模块**：
   * 在 `system_server` 进程中拦截 Binder IPC 调用，具体为 `NetworkCapabilities`、`NetworkInfo` 和 `LinkProperties` 的 Parcel 序列化。这样无需向目标应用进程注入代码，也能避免通过 Java API 泄露 VPN 信息。

---

### 主要功能

* **100% 隐蔽（内置模式）**：隐藏逻辑直接编译进内核，不使用外部模块和 kretprobe Hook。保护机制不会受系统调用计时攻击影响，也不会被用户空间分析或反篡改 SDK 检测到。
* **不限数量的目标应用**：过滤机制支持不限数量的目标应用。有效 UID 列表由守护进程动态计算，避免系统缓冲区溢出。
* **列表模式（黑名单 / 白名单）**：
  * *黑名单模式*：仅向明确选中的应用隐藏 VPN 接口和套接字选项。
  * *白名单模式*：向所有已安装应用隐藏 VPN，仅对例外列表中明确选中的应用保留可见性。
* **动态端口规则**：通过 `security_socket_connect` / `security_socket_bind` Hook，在内核层阻止或重定向连接到本地 VPN 守护进程端口。端口规则可动态管理，不依赖 iptables 规则或 ProcFS。
* **端口访问监控**：记录目标应用请求访问的网络端口。
* **动态 Hook 配置**：通过 `/dev/vpnhide_ctrl` 控制设备动态切换隐藏参数和保护级别，无需重启即可生效。
* **自动隐藏控制应用**：VPN 客户端应用和 VPNHide Next 管理器会在运行时向目标应用隐藏自身。
* **工作资料支持**：在主用户与隔离的 Android 工作资料（多用户 / 工作资料）之间完全隔离隐藏逻辑和目标列表。

---

### 保护级别

可在概览页动态切换保护级别，以配置已激活的内核 Hook：

| 级别 | 说明 | 覆盖的检测向量 |
|---|---|---|
| **最低（Min）** | 基础网络隔离 | 过滤接口枚举（`ioctl`、netlink）、阻止端口，并隐藏 `/proc/net/route` 中的路由。 |
| **平均（Avg）** | 套接字和网络参数隔离 | 包含 **Min** 的全部功能；拦截 `getsockopt`/`setsockopt`，伪装 MTU、MSS、`TCP_INFO`、`SO_BINDTODEVICE` 和 `SO_TIMESTAMPING`，并防止套接字标记（`SO_MARK`）泄露。 |
| **最高（Max）** | 全面的系统隔离 | 包含 **Avg** 的全部功能；隐藏 `/proc/net/{dev,if_inet6,fib_trie}` 和 `/sys/class/net` 中的文件，清零 eBPF 流量统计，防护 UDP 计时攻击和 IPv6 链路本地暴力探测，并隐藏 VPN qdisc。 |

---

### 诊断与监控

应用内置诊断模块，执行 **44 项自动检查**（37 项原生层检查和 7 项 Java 层检查），用于验证所有已知检测向量的隐藏完整性。

拦截统计由守护进程累积到内存环形缓冲区，并可通过抽象套接字 `vpnhide.stats.v1` 实时查询。

---

### 截图

<div align="center">

| 概览 | 应用列表 | 统计 |
|:-:|:-:|:-:|
| <img src="assets/screenshots/dushboard.jpg" width="200"> | <img src="assets/screenshots/apps_picker.jpg" width="200"> | <img src="assets/screenshots/statistics_screen.jpg" width="200"> |
| **Hook 设置** | **应用设置** | **诊断** |
| <img src="assets/screenshots/hook_settings.jpg" width="200"> | <img src="assets/screenshots/app_settings.jpg" width="200"> | <img src="assets/screenshots/diagnostics_screen.jpg" width="200"> |

</div>

---

### 致谢

* [KernelSU](https://github.com/tiann/KernelSU) / [KernelSU-Next](https://github.com/rifsxd/KernelSU-Next) — 出色的 Root 解决方案。
* [wildkernels](https://github.com/wildkernels) — 内置模式的基础。
* [okhsunrog/vpnhide](https://github.com/okhsunrog/vpnhide) — 本项目的基础和灵感来源。
* [LSPosed](https://github.com/LSPosed/LSPosed) — 强大的系统框架 Hook 机制。
* [SUSFS](https://gitlab.com/ephxius/susfs4ksu) — 内核空间中的文件和路径隐藏概念。
