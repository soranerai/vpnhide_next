// SPDX-License-Identifier: MIT
/*
 * vpnhide_kmod — kernel module that hides VPN network interfaces from
 * selected Android apps by filtering ioctl, netlink, and procfs
 * responses based on the calling process's UID.
 *
 * Uses kretprobes so no modification of the running kernel is needed;
 * works on stock Android GKI kernels with CONFIG_KPROBES=y.
 *
 * Hooks:
 *   - dev_ioctl: filters SIOCGIFFLAGS / SIOCGIFNAME / SIOCGIFMTU / etc.
 *   - sock_ioctl: filters SIOCGIFCONF interface enumeration
 *   - rtnl_fill_ifinfo: filters RTM_NEWLINK netlink dumps (getifaddrs)
 *   - inet6_fill_ifaddr: filters RTM_GETADDR IPv6 responses (getifaddrs)
 *   - inet_fill_ifaddr: filters RTM_GETADDR IPv4 responses (getifaddrs)
 *   - fib_route_seq_show: filters /proc/net/route entries
 *
 * Target UIDs are written to /proc/vpnhide_targets from userspace.
 *
 * Architecture: arm64 only. The handlers read syscall arguments via
 * `regs->regs[N]` (AAPCS64 calling convention). On other architectures
 * those slots have a different meaning, so the build is gated below.
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/version.h>
#include <linux/kprobes.h>
#include <linux/slab.h>
#include <linux/cred.h>
#include <linux/uidgid.h>
#include <linux/string.h>
#include <linux/net.h>
#include <linux/if.h>
#include <linux/uaccess.h>
#include <linux/seq_file.h>
#include <linux/proc_fs.h>
#include <linux/netdevice.h>
#include <linux/rtnetlink.h>
#include <linux/skbuff.h>
#include <linux/inetdevice.h>
#include <linux/miscdevice.h>
#include <linux/fs.h>
#include <net/if_inet6.h>
#include <net/ip_fib.h>
#include <net/nexthop.h>
#include <net/ip6_fib.h>
#include <net/ip6_route.h>
#include <net/route.h>
#include <net/fib_rules.h>
#include <linux/socket.h>
#include <linux/in.h>
#include <linux/in6.h>
#include <net/ipv6.h>
#include <linux/bpf.h>
#include <linux/file.h>

#include "generated/iface_lists.h"
#include "include/vpnhide.h"

struct vh_stats_key {
	u32 uid;
	u32 tag;
	u32 counterSet;
	u32 ifaceIndex;
};

struct vh_stats_value {
	u64 rxBytes;
	u64 rxPackets;
	u64 txBytes;
	u64 txPackets;
};

static bool g_stats_pkts_first __read_mostly = false;

static inline u64 sv_rx_bytes(const struct vh_stats_value *sv)
{
	return g_stats_pkts_first ? sv->rxPackets : sv->rxBytes;
}
static inline u64 sv_tx_bytes(const struct vh_stats_value *sv)
{
	return g_stats_pkts_first ? sv->txPackets : sv->txBytes;
}
static inline u64 sv_rx_pkts(const struct vh_stats_value *sv)
{
	return g_stats_pkts_first ? sv->rxBytes : sv->rxPackets;
}
static inline u64 sv_tx_pkts(const struct vh_stats_value *sv)
{
	return g_stats_pkts_first ? sv->txBytes : sv->txPackets;
}
static inline void sv_add(struct vh_stats_value *dst,
			  const struct vh_stats_value *src)
{
	if (g_stats_pkts_first) {
		dst->rxPackets +=
			src->rxPackets; /* rxPackets field = rxBytes */
		dst->rxBytes += src->rxBytes; /* rxBytes field  = rxPackets */
		dst->txPackets += src->txPackets;
		dst->txBytes += src->txBytes;
	} else {
		dst->rxBytes += src->rxBytes;
		dst->rxPackets += src->rxPackets;
		dst->txBytes += src->txBytes;
		dst->txPackets += src->txPackets;
	}
}

#ifndef CONFIG_ARM64
#endif

#ifndef IP_MTU_DISCOVER
#define IP_MTU_DISCOVER 10
#endif

#ifndef IP_PMTUDISC_DONT
#define IP_PMTUDISC_DONT 0
#endif

#ifndef IP_PMTUDISC_DO
#define IP_PMTUDISC_DO 2
#endif

#ifndef IPV6_MTU_DISCOVER
#define IPV6_MTU_DISCOVER 23
#endif

#ifndef IPV6_PMTUDISC_DONT
#define IPV6_PMTUDISC_DONT 0
#endif

#ifndef IPV6_PMTUDISC_DO
#define IPV6_PMTUDISC_DO 2
#endif

#define MODNAME "vpnhide"

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 11, 0)
#define vh_fd_file(f) fd_file(f)
#else
#define vh_fd_file(f) ((f).file)
#endif

#ifndef BPF_FS_MAGIC
#define BPF_FS_MAGIC 0xcafe4a4b
#endif

/*
 * Pre-allocated kretprobe instance pool size, applied to every probe.
 * Default kernel `register_kretprobe` falls back to NR_CPUS*2 (≈ 18 on
 * a 9-core Pixel 8 Pro), which is too low for hot ioctl/netlink paths
 * under multi-app concurrency — exhausted pool causes silent
 * `nmissed++` and the return handler skipped, which surfaces as a VPN
 * iface leaking through a single probe call.
 *
 * 64 covers a comfortable working set (apps × threads doing
 * getifaddrs/SIOCGIFCONF/route reads at once) without burning
 * meaningful memory: 6 probes × 64 instances × ~80 B ≈ 30 KB total.
 */
#define VPNHIDE_KRETPROBE_MAXACTIVE 64

/* ------------------------------------------------------------------ */
/*  Debug logging — toggled via /proc/vpnhide_debug                   */
/* ------------------------------------------------------------------ */

static bool debug_enabled;
static unsigned int active_hooks_mask = 0xFFFFFFFF;

static inline bool is_hook_active(int index)
{
	return (READ_ONCE(active_hooks_mask) & (1 << index)) != 0;
}

/*
 * `debug_enabled` is a single bool, written from /proc/vpnhide_debug
 * and read from every probe handler. Use READ_ONCE/WRITE_ONCE so the
 * compiler doesn't tear the access or hoist it across the probe-hot
 * path — kosher kernel style for unsynchronised flags.
 */
#define vpnhide_dbg(fmt, ...)                                     \
	do {                                                      \
		if (READ_ONCE(debug_enabled))                     \
			pr_info(MODNAME ": " fmt, ##__VA_ARGS__); \
	} while (0)

/* ------------------------------------------------------------------ */
/*  VPN interface name matching — see data/interfaces.toml            */
/* ------------------------------------------------------------------ */

#define is_vpn_ifname(name) vpnhide_iface_is_vpn(name)

struct vpnhide_targets {
	int count;
	uid_t uids[MAX_TARGET_UIDS];
	struct rcu_head rcu;
};

struct vpnhide_port_targets {
	int count;
	struct vpnhide_uid_port_rules targets[MAX_TARGET_UIDS];
	struct rcu_head rcu;
};

static struct vpnhide_targets __rcu *global_targets;
static DEFINE_SPINLOCK(targets_update_lock);

static struct vpnhide_port_targets __rcu *global_port_targets;
static DEFINE_SPINLOCK(port_targets_update_lock);

struct vpnhide_iface_prefixes {
	int count;
	char prefixes[MAX_IFACE_PREFIXES][MAX_IFACE_LEN];
	struct rcu_head rcu;
};

static struct vpnhide_iface_prefixes __rcu *global_iface_prefixes;
static DEFINE_MUTEX(iface_prefixes_lock);

static struct vpnhide_spoof_ip global_spoof_ip;
static DEFINE_SPINLOCK(spoof_ip_lock);

/* Ifindex of the cover (non-VPN) interface, sent by the daemon.
 * Used in vh_stats_map_lookup to avoid scanning all interfaces.
 * 0 means not set yet. */
static atomic_t global_cover_ifindex = ATOMIC_INIT(0);

static bool vpnhide_is_vpn_ifname(const char *name)
{
	struct vpnhide_iface_prefixes *p;
	int i;
	bool found = false;

	if (vpnhide_iface_is_vpn(name))
		return true;

	rcu_read_lock();
	p = rcu_dereference(global_iface_prefixes);
	if (p) {
		for (i = 0; i < p->count; i++) {
			if (vpnhide_iface_starts_with_ci(name,
							 p->prefixes[i])) {
				found = true;
				break;
			}
		}
	}
	rcu_read_unlock();
	return found;
}

#undef is_vpn_ifname
#define is_vpn_ifname(name) vpnhide_is_vpn_ifname(name)

static bool is_target_uid_val(uid_t uid)
{
	struct vpnhide_targets *t;
	bool found = false;
	int i;

	rcu_read_lock();
	t = rcu_dereference(global_targets);
	if (t) {
		for (i = 0; i < t->count; i++) {
			if (t->uids[i] == uid) {
				found = true;
				break;
			}
		}
	}
	rcu_read_unlock();
	return found;
}

static bool is_target_uid(void)
{
	return is_target_uid_val(from_kuid(&init_user_ns, current_uid()));
}

#define BUCKETS_COUNT 30

struct kmod_uid_rolling_stats {
	uid_t uid;
	u32 ioctl_counts[BUCKETS_COUNT];
	u32 netlink_counts[BUCKETS_COUNT];
	u32 connect_counts[BUCKETS_COUNT];
	u32 getname_counts[BUCKETS_COUNT];
	u64 bucket_times[BUCKETS_COUNT];
};

static struct kmod_uid_rolling_stats kmod_stats[MAX_TARGET_UIDS];
static int kmod_stats_count = 0;
static DEFINE_SPINLOCK(kmod_stats_lock);

static void record_kmod_intercept(uid_t uid, int type)
{
	int i;
	unsigned long flags;
	u64 now_min = ktime_get_real_seconds() / 60;
	int idx = (int)(now_min % BUCKETS_COUNT);

	if (uid == 0 || uid == 1000)
		return;

	spin_lock_irqsave(&kmod_stats_lock, flags);
	for (i = 0; i < kmod_stats_count; i++) {
		if (kmod_stats[i].uid == uid) {
			if (kmod_stats[i].bucket_times[idx] != now_min) {
				kmod_stats[i].ioctl_counts[idx] = 0;
				kmod_stats[i].netlink_counts[idx] = 0;
				kmod_stats[i].connect_counts[idx] = 0;
				kmod_stats[i].getname_counts[idx] = 0;
				kmod_stats[i].bucket_times[idx] = now_min;
			}
			if (type == 1)
				kmod_stats[i].ioctl_counts[idx]++;
			else if (type == 2)
				kmod_stats[i].netlink_counts[idx]++;
			else if (type == 3)
				kmod_stats[i].connect_counts[idx]++;
			else if (type == 4)
				kmod_stats[i].getname_counts[idx]++;
			spin_unlock_irqrestore(&kmod_stats_lock, flags);
			return;
		}
	}

	if (kmod_stats_count < MAX_TARGET_UIDS) {
		kmod_stats[kmod_stats_count].uid = uid;
		memset(kmod_stats[kmod_stats_count].ioctl_counts, 0,
		       sizeof(kmod_stats[kmod_stats_count].ioctl_counts));
		memset(kmod_stats[kmod_stats_count].netlink_counts, 0,
		       sizeof(kmod_stats[kmod_stats_count].netlink_counts));
		memset(kmod_stats[kmod_stats_count].connect_counts, 0,
		       sizeof(kmod_stats[kmod_stats_count].connect_counts));
		memset(kmod_stats[kmod_stats_count].getname_counts, 0,
		       sizeof(kmod_stats[kmod_stats_count].getname_counts));
		memset(kmod_stats[kmod_stats_count].bucket_times, 0,
		       sizeof(kmod_stats[kmod_stats_count].bucket_times));

		kmod_stats[kmod_stats_count].bucket_times[idx] = now_min;
		if (type == 1)
			kmod_stats[kmod_stats_count].ioctl_counts[idx] = 1;
		else if (type == 2)
			kmod_stats[kmod_stats_count].netlink_counts[idx] = 1;
		else if (type == 3)
			kmod_stats[kmod_stats_count].connect_counts[idx] = 1;
		else if (type == 4)
			kmod_stats[kmod_stats_count].getname_counts[idx] = 1;

		kmod_stats_count++;
	}
	spin_unlock_irqrestore(&kmod_stats_lock, flags);
}

/* ================================================================== */
/*  Hook 1: dev_ioctl — all per-interface ioctls                      */
/*  Android source path: net/core/dev_ioctl.c                         */
/*                                                                    */
/*  dev_ioctl() on GKI 6.1:                                           */
/*    int dev_ioctl(struct net *net, unsigned int cmd,                */
/*                  struct ifreq *ifr, void __user *data,             */
/*                  bool *need_copyout)                               */
/*  arm64: x0=net, x1=cmd, x2=ifr (KERNEL ptr), x3=data (__user)      */
/*                                                                    */
/*  Covers SIOCGIFFLAGS, SIOCGIFNAME, SIOCGIFMTU, SIOCGIFINDEX,       */
/*  SIOCGIFHWADDR, SIOCGIFADDR, and any other cmd that goes through   */
/*  dev_ioctl with a VPN interface name in ifr_name. Returns ENODEV   */
/*  for all of them.                                                  */
/*                                                                    */
/*  Note: SIOCGIFCONF goes through sock_ioctl -> dev_ifconf, not      */
/*  through dev_ioctl, so it is not covered here.                     */
/* ================================================================== */

struct dev_ioctl_data {
	unsigned int cmd;
	struct ifreq *kifr; /* kernel pointer, saved from x2 */
	bool active; /* true = caller is target UID, run ret handler */
};

static int dev_ioctl_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct dev_ioctl_data *data;
	if (!is_hook_active(0))
		return 1;
	data = (void *)ri->data;

	data->cmd = (unsigned int)regs->regs[1];
	data->kifr = (struct ifreq *)regs->regs[2];
	data->active = is_target_uid();

	vpnhide_dbg("dev_ioctl_entry: uid=%u target=%d cmd=0x%x\n",
		    from_kuid(&init_user_ns, current_uid()), data->active,
		    data->cmd);
	return 0;
}

static int dev_ioctl_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct dev_ioctl_data *data = (void *)ri->data;
	char name[IFNAMSIZ];

	if (!data->active || regs_return_value(regs) != 0)
		return 0;

	/*
	 * ifr (x2) is a KERNEL pointer — the caller already did
	 * copy_from_user into a stack-local ifreq. Read via direct
	 * dereference; copy_from_user would EFAULT under ARM64 PAN.
	 */
	if (!data->kifr)
		return 0;

	memcpy(name, data->kifr->ifr_name, IFNAMSIZ);
	name[IFNAMSIZ - 1] = '\0';

	if (is_vpn_ifname(name)) {
		vpnhide_dbg("dev_ioctl_ret: hiding iface=%s cmd=0x%x\n", name,
			    data->cmd);
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      1);
		regs_set_return_value(regs, -ENODEV);
	}

	return 0;
}

static struct kretprobe dev_ioctl_krp = {
	.handler = dev_ioctl_ret,
	.entry_handler = dev_ioctl_entry,
	.data_size = sizeof(struct dev_ioctl_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "dev_ioctl",
};

/* ================================================================== */
/*  Hook 2: sock_ioctl — SIOCGIFCONF interface enumeration            */
/*  Android source path: net/socket.c                                 */
/*                                                                    */
/*  Why sock_ioctl instead of dev_ifconf?                             */
/*                                                                    */
/*  On GKI 5.10 kernels built with Clang LTO (all stock Android       */
/*  devices), the linker inlines dev_ifconf() into sock_do_ioctl().   */
/*  The symbol "dev_ifconf" stays in kallsyms as a dead stub, so      */
/*  kretprobe registration succeeds but the probe never fires.        */
/*                                                                    */
/*  On 6.1+, SIOCGIFCONF was moved out of sock_do_ioctl() into        */
/*  sock_ioctl() directly (handled in the switch statement), so       */
/*  hooking sock_do_ioctl would miss it on newer kernels.             */
/*                                                                    */
/*  sock_ioctl is the correct hook point because:                     */
/*  1. It is the file_operations->unlocked_ioctl callback for socket  */
/*     fds — used as a function pointer, so LTO cannot inline it.     */
/*  2. ALL socket ioctls, including SIOCGIFCONF, pass through it on   */
/*     every kernel version (5.10 through 6.12+).                     */
/*  3. After sock_ioctl returns, the ifconf data (ifreq array +       */
/*     ifc_len) is already in userspace — we filter it uniformly via  */
/*     copy_from_user/copy_to_user regardless of kernel version.      */
/*                                                                    */
/*  Performance: entry handler checks cmd == SIOCGIFCONF first (one   */
/*  compare), then is_target_uid(). For all other ioctls, overhead    */
/*  is a single branch. SIOCGIFCONF is rare (once per getifaddrs).    */
/* ================================================================== */

struct sock_ioctl_data {
	void __user *argp;
	bool target;
};

/* Handle SIOCGIFCONF filtering */

/*
 * Why user-memory access is OK here:
 *
 * `sock_ioctl_ret` runs as a kretprobe return handler — same process
 * context that issued the SIOCGIFCONF syscall, kernel mode, original
 * task is still mapped and addressable. copy_from_user/copy_to_user
 * are safe in this context (it's the same userspace the original
 * sock_ioctl handler accessed). PAN/uaccess primitives are honoured.
 *
 * Faults are handled cleanly: if the user buffer was unmapped or
 * raced, the copy fails with -EFAULT and we report COPY_FAULT to the
 * caller, who skips the ifc_len rewrite to avoid a half-filtered
 * array (`buffer compacted, length unchanged`) escaping to userspace.
 */
enum filter_ifconf_result {
	FILTER_IFCONF_NO_CHANGE,
	FILTER_IFCONF_CHANGED,
	FILTER_IFCONF_COPY_FAULT,
};

/* Compact VPN entries out of the userspace ifreq array. The caller is
 * responsible for updating `ifc_len` only on FILTER_IFCONF_CHANGED. */
static enum filter_ifconf_result filter_ifconf_buf(struct ifreq __user *usr_ifr,
						   int n, int *out_len)
{
	struct ifreq tmp;
	int i, dst = 0;

	for (i = 0; i < n; i++) {
		if (copy_from_user(&tmp, &usr_ifr[i], sizeof(tmp)))
			return FILTER_IFCONF_COPY_FAULT;
		tmp.ifr_name[IFNAMSIZ - 1] = '\0';
		if (is_vpn_ifname(tmp.ifr_name))
			continue;
		if (dst != i) {
			if (copy_to_user(&usr_ifr[dst], &tmp, sizeof(tmp)))
				return FILTER_IFCONF_COPY_FAULT;
		}
		dst++;
	}

	if (dst == n)
		return FILTER_IFCONF_NO_CHANGE;
	*out_len = dst * (int)sizeof(struct ifreq);
	return FILTER_IFCONF_CHANGED;
}

static int sock_ioctl_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct sock_ioctl_data *data = (void *)ri->data;
	struct ifconf __user *uifc;
	struct ifconf ifc;
	int orig_len;
	enum filter_ifconf_result res;

	if (!data->target)
		return 0;

	vpnhide_dbg("sock_ioctl_ret: retval=%ld argp=%px\n",
		    regs_return_value(regs), data->argp);

	if (regs_return_value(regs) != 0 || !data->argp)
		return 0;

	uifc = data->argp;
	if (copy_from_user(&ifc, uifc, sizeof(ifc)))
		return 0;
	if (!ifc.ifc_req || ifc.ifc_len <= 0)
		return 0;

	orig_len = ifc.ifc_len;
	res = filter_ifconf_buf(ifc.ifc_req,
				ifc.ifc_len / (int)sizeof(struct ifreq),
				&ifc.ifc_len);

	if (res == FILTER_IFCONF_COPY_FAULT) {
		/*
		 * Partial copy failure — buffer may already be
		 * half-rewritten. Don't update ifc_len: a shorter
		 * length on a partially-compacted buffer hides VPN
		 * entries past the truncation but lets earlier ones
		 * through, which is worse than just leaving
		 * everything visible. Userspace sees the original
		 * length and the (mostly-original) buffer.
		 */
		vpnhide_dbg(
			"ifconf: copy fault during filter; ifc_len untouched\n");
		return 0;
	}

	if (res == FILTER_IFCONF_CHANGED) {
		if (put_user(ifc.ifc_len, &uifc->ifc_len)) {
			vpnhide_dbg(
				"ifconf: put_user(ifc_len=%d) failed; userspace will see compacted buffer with stale length\n",
				ifc.ifc_len);
			return 0;
		}
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      1);
		vpnhide_dbg("ifconf filtered %d -> %d bytes\n", orig_len,
			    ifc.ifc_len);
	}

	return 0;
}

/* ================================================================== */
/*  Hook 2b: sock_setsockopt — Aikido Bind Sabotage                    */
/*  Android source path: net/socket.c                                 */
/*                                                                    */
/*  sock_setsockopt(struct socket *sock, int level, int optname,      */
/*                  sockptr_t optval, unsigned int optlen)            */
/*                                                                    */
/*  If a target app tries to SO_BINDTODEVICE or SO_BINDTOIFINDEX to   */
/*  a VPN interface, we sabotage the arguments on the fly. We change  */
/*  optlen to 0. The kernel interprets this as "remove binding", does */
/*  nothing harmful, and returns 0 (Success) to the app.              */
/* ================================================================== */

struct sock_setsockopt_data {
	bool override_ret;
	int deny_errno;
};

/*
 * sock_setsockopt ABI differs between kernel versions:
 *
 * 5.10/5.15: (struct socket*, int, int, char __user*, unsigned int)
 *   arm64: x0=sock  x1=level  x2=optname  x3=optval  x4=optlen
 *
 * 6.0+: sockptr_t expands to two words in AAPCS64:
 *   arm64: x0=sock  x1=level  x2=optname  x3=optval.user
 *          x4=optval.is_kernel  x5=optlen
 */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 0, 0)
#define SETSOCKOPT_REG_OPTVAL    3
#define SETSOCKOPT_REG_IS_KERNEL 4
#define SETSOCKOPT_REG_OPTLEN    5
#else
#define SETSOCKOPT_REG_OPTVAL    3
#define SETSOCKOPT_REG_OPTLEN    4
#endif

static int sock_setsockopt_entry(struct kretprobe_instance *ri,
				 struct pt_regs *regs)
{
	struct sock_setsockopt_data *sdata;
	int level = (int)regs->regs[1];
	int optname = (int)regs->regs[2];
	void __user *optval_ptr = (void __user *)regs->regs[SETSOCKOPT_REG_OPTVAL];
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 0, 0)
	bool is_kernel = (bool)(regs->regs[SETSOCKOPT_REG_IS_KERNEL] & 1);
#else
	bool is_kernel = false;
#endif
	int optlen = (int)regs->regs[SETSOCKOPT_REG_OPTLEN];
	char name[IFNAMSIZ];

	if (!is_hook_active(11))
		return 1;

	sdata = (void *)ri->data;
	sdata->override_ret = false;
	sdata->deny_errno = 0;

	if (level == 0x5648 && optname == 0x88) {
		uid_t uid = from_kuid(&init_user_ns, current_uid());
		if (uid == 1000 || uid == 0) {
			struct vpnhide_spoof_ip sip;
			if (optlen == sizeof(sip)) {
				if (copy_from_user(&sip, optval_ptr,
						   sizeof(sip)) == 0) {
					spin_lock(&spoof_ip_lock);
					global_spoof_ip = sip;
					spin_unlock(&spoof_ip_lock);
					vpnhide_dbg(
						"setsockopt: updated spoof IP: IPv4=%pI4 (%d), IPv6=%pI6c (%d)\n",
						&sip.ipv4_addr, sip.has_ipv4,
						sip.ipv6_addr, sip.has_ipv6);
					sdata->override_ret = true;
				}
			}
		}
		return 0;
	}

	if (!is_target_uid())
		return 0;

	if (is_kernel)
		return 0;

	if (level == SOL_SOCKET) {
		if (optname == SO_BINDTODEVICE) {
			if (optlen <= 0)
				return 0;
			if (optlen > IFNAMSIZ)
				optlen = IFNAMSIZ;

			if (copy_from_user(name, optval_ptr, optlen))
				return 0;
			name[optlen - 1] = '\0';

			if (is_vpn_ifname(name)) {
				vpnhide_dbg(
					"sock_setsockopt: denying SO_BINDTODEVICE to VPN iface '%s' with ENODEV\n",
					name);
				sdata->override_ret = true;
				sdata->deny_errno = ENODEV;
			}
		} else if (optname == SO_BINDTOIFINDEX) {
			int ifindex;
			struct net_device *dev;
			struct net *net;
			struct socket *sock;

			if (optlen != sizeof(int))
				return 0;
			if (copy_from_user(&ifindex, optval_ptr, sizeof(int)))
				return 0;

			if (ifindex <= 0)
				return 0;
			sock = (struct socket *)regs->regs[0];
			net = sock && sock->sk ?
				      sock_net(sock->sk) :
				      (current->nsproxy ?
					       current->nsproxy->net_ns :
					       &init_net);
			rcu_read_lock();
			dev = dev_get_by_index_rcu(net, ifindex);
			if (dev && is_vpn_ifname(dev->name)) {
				vpnhide_dbg(
					"sock_setsockopt: denying SO_BINDTOIFINDEX %d (%s) with ENODEV\n",
					ifindex, dev->name);
				sdata->override_ret = true;
				sdata->deny_errno = ENODEV;
			}
			rcu_read_unlock();
		} else if (optname == SO_MARK) {
			int mark;
			if (optlen != sizeof(int))
				return 0;
			if (copy_from_user(&mark, optval_ptr, sizeof(int)))
				return 0;

			if (mark != 0) {
				int zero_mark = 0;
				vpnhide_dbg(
					"sock_setsockopt: target app tried to set SO_MARK to 0x%x, overriding to 0\n",
					mark);
				if (copy_to_user(optval_ptr, &zero_mark,
						 sizeof(int))) {
					vpnhide_dbg(
						"sock_setsockopt: failed to overwrite SO_MARK with 0\n");
				}
			}
		}
	} else if (level == IPPROTO_IP) {
		if (optname == IP_MTU_DISCOVER) {
			int discover;
			if (optlen == sizeof(int)) {
				if (copy_from_user(&discover, optval_ptr,
						   sizeof(int)) == 0) {
					if (discover != IP_PMTUDISC_DONT) {
						int fake_disc =
							IP_PMTUDISC_DONT;
						if (copy_to_user(optval_ptr,
								 &fake_disc,
								 sizeof(int)) ==
						    0) {
							vpnhide_dbg(
								"sock_setsockopt: spoofed IP_MTU_DISCOVER from %d to IP_PMTUDISC_DONT\n",
								discover);
						}
					}
				}
			}
		}
	} else if (level == IPPROTO_IPV6) {
		if (optname == IPV6_MTU_DISCOVER) {
			int discover;
			if (optlen == sizeof(int)) {
				if (copy_from_user(&discover, optval_ptr,
						   sizeof(int)) == 0) {
					if (discover != IPV6_PMTUDISC_DONT) {
						int fake_disc =
							IPV6_PMTUDISC_DONT;
						if (copy_to_user(optval_ptr,
								 &fake_disc,
								 sizeof(int)) ==
						    0) {
							vpnhide_dbg(
								"sock_setsockopt: spoofed IPV6_MTU_DISCOVER from %d to IPV6_PMTUDISC_DONT\n",
								discover);
						}
					}
				}
			}
		}
	}

	return 0;
}

static int sock_setsockopt_ret(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	struct sock_setsockopt_data *sdata = (void *)ri->data;
	if (sdata->override_ret) {
		regs_set_return_value(regs, sdata->deny_errno ? -sdata->deny_errno : 0);
	}
	return 0;
}

static struct kretprobe sock_setsockopt_krp = {
	.handler = sock_setsockopt_ret,
	.entry_handler = sock_setsockopt_entry,
	.data_size = sizeof(struct sock_setsockopt_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "sock_setsockopt",
};

static struct kretprobe sock_common_setsockopt_krp = {
	.handler = sock_setsockopt_ret,
	.entry_handler = sock_setsockopt_entry,
	.data_size = sizeof(struct sock_setsockopt_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "sock_common_setsockopt",
};

/* ================================================================== */
/*  Hook 2c: sock_getsockopt & sock_common_getsockopt — Bind Query    */
/*           Sabotage                                                 */
/*  Android source path:                                              */
/*    - sock_getsockopt: net/socket.c                                 */
/*    - sock_common_getsockopt: net/core/sock.c                       */
/*                                                                    */
/*  sock_getsockopt(struct socket *sock, int level, int optname,      */
/*                  char __user *optval, int __user *optlen)          */
/*  arm64: x1=level, x2=optname, x3=optval, x4=optlen                 */
/* ================================================================== */

struct sock_getsockopt_data {
	int level;
	int optname;
	void __user *optval;
	int __user *optlen;
	struct net *net;
	bool active;
};

static int sock_getsockopt_entry(struct kretprobe_instance *ri,
				 struct pt_regs *regs)
{
	struct sock_getsockopt_data *data;
	struct socket *sock = (struct socket *)regs->regs[0];

	if (!is_hook_active(12))
		return 1;

	data = (void *)ri->data;

	data->level = (int)regs->regs[1];
	data->optname = (int)regs->regs[2];
	data->optval = (void __user *)regs->regs[3];
	data->optlen = (int __user *)regs->regs[4];
	data->net = sock && sock->sk ?
			    sock_net(sock->sk) :
			    (current->nsproxy ? current->nsproxy->net_ns :
						&init_net);
	data->active = is_target_uid();

	return 0;
}

static int sock_getsockopt_ret(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	struct sock_getsockopt_data *data = (void *)ri->data;
	int ret = regs_return_value(regs);

	if (!data->active || ret != 0)
		return 0;

	if (data->level == IPPROTO_IP && data->optname == IP_MTU) {
		int mtu = 0;
		int len = 0;
		if (get_user(len, data->optlen) == 0 && len >= sizeof(int)) {
			if (copy_from_user(&mtu, data->optval, sizeof(int)) ==
			    0) {
				if (mtu > 0 && mtu < 1500) {
					int fake_mtu = 1500;
					if (copy_to_user(data->optval,
							 &fake_mtu,
							 sizeof(int)) == 0) {
						vpnhide_dbg(
							"sock_getsockopt_ret: spoofed IP_MTU from %d to 1500\n",
							mtu);
					}
				}
			}
		}
		return 0;
	}

	if (data->level == IPPROTO_IPV6 && data->optname == IPV6_MTU) {
		int mtu = 0;
		int len = 0;
		if (get_user(len, data->optlen) == 0 && len >= sizeof(int)) {
			if (copy_from_user(&mtu, data->optval, sizeof(int)) ==
			    0) {
				if (mtu > 0 && mtu < 1500) {
					int fake_mtu = 1500;
					if (copy_to_user(data->optval,
							 &fake_mtu,
							 sizeof(int)) == 0) {
						vpnhide_dbg(
							"sock_getsockopt_ret: spoofed IPV6_MTU from %d to 1500\n",
							mtu);
					}
				}
			}
		}
		return 0;
	}

	if (data->level == IPPROTO_IP && data->optname == IP_MTU_DISCOVER) {
		int discover = 0;
		int len = 0;
		if (get_user(len, data->optlen) == 0 && len >= sizeof(int)) {
			if (copy_from_user(&discover, data->optval,
					   sizeof(int)) == 0) {
				if (discover == IP_PMTUDISC_DONT) {
					int fake_disc = IP_PMTUDISC_DO;
					if (copy_to_user(data->optval,
							 &fake_disc,
							 sizeof(int)) == 0) {
						vpnhide_dbg(
							"sock_getsockopt_ret: spoofed IP_MTU_DISCOVER to IP_PMTUDISC_DO\n");
					}
				}
			}
		}
		return 0;
	}

	if (data->level == IPPROTO_IPV6 && data->optname == IPV6_MTU_DISCOVER) {
		int discover = 0;
		int len = 0;
		if (get_user(len, data->optlen) == 0 && len >= sizeof(int)) {
			if (copy_from_user(&discover, data->optval,
					   sizeof(int)) == 0) {
				if (discover == IPV6_PMTUDISC_DONT) {
					int fake_disc = IPV6_PMTUDISC_DO;
					if (copy_to_user(data->optval,
							 &fake_disc,
							 sizeof(int)) == 0) {
						vpnhide_dbg(
							"sock_getsockopt_ret: spoofed IPV6_MTU_DISCOVER to IPV6_PMTUDISC_DO\n");
					}
				}
			}
		}
		return 0;
	}

	if (data->level == IPPROTO_TCP && data->optname == TCP_MAXSEG) {
		int mss = 0;
		int len = 0;
		if (get_user(len, data->optlen) == 0 && len >= sizeof(int)) {
			if (copy_from_user(&mss, data->optval, sizeof(int)) ==
			    0) {
				if (mss > 0 && mss < 1460) {
					int fake_mss = 1460;
					if (copy_to_user(data->optval,
							 &fake_mss,
							 sizeof(int)) == 0) {
						vpnhide_dbg(
							"sock_getsockopt_ret: spoofed TCP_MAXSEG from %d to 1460\n",
							mss);
					}
				}
			}
		}
		return 0;
	}

	if (data->level != SOL_SOCKET)
		return 0;

	if (data->optname == SO_BINDTODEVICE) {
		int len;
		char name[IFNAMSIZ];

		if (get_user(len, data->optlen))
			return 0;

		if (len <= 0)
			return 0;

		if (len > IFNAMSIZ)
			len = IFNAMSIZ;

		if (copy_from_user(name, data->optval, len))
			return 0;
		name[len - 1] = '\0';

		if (is_vpn_ifname(name)) {
			char zero = '\0';
			int zero_len = 0;

			vpnhide_dbg(
				"sock_getsockopt_ret: spoofing empty SO_BINDTODEVICE (was %s)\n",
				name);

			if (copy_to_user(data->optval, &zero, 1) == 0 &&
			    copy_to_user(data->optlen, &zero_len,
					 sizeof(int)) == 0) {
				/* Success */
			}
		}
	} else if (data->optname == SO_BINDTOIFINDEX) {
		int ifindex;
		struct net_device *dev;
		struct net *net = data->net;

		if (copy_from_user(&ifindex, data->optval, sizeof(int)))
			return 0;

		if (ifindex <= 0)
			return 0;

		rcu_read_lock();
		dev = dev_get_by_index_rcu(net, ifindex);
		if (dev && is_vpn_ifname(dev->name)) {
			int zero_idx = 0;
			vpnhide_dbg(
				"sock_getsockopt_ret: spoofing SO_BINDTOIFINDEX %d (%s) to 0\n",
				ifindex, dev->name);
			rcu_read_unlock();
			if (copy_to_user(data->optval, &zero_idx,
					 sizeof(int))) {
				/* error */
			}
		} else {
			rcu_read_unlock();
		}
	}

	return 0;
}

static struct kretprobe sock_getsockopt_krp = {
	.entry_handler = sock_getsockopt_entry,
	.handler = sock_getsockopt_ret,
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.data_size = sizeof(struct sock_getsockopt_data),
	.kp.symbol_name = "sock_getsockopt",
};

static struct kretprobe sock_common_getsockopt_krp = {
	.entry_handler = sock_getsockopt_entry,
	.handler = sock_getsockopt_ret,
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.data_size = sizeof(struct sock_getsockopt_data),
	.kp.symbol_name = "sock_common_getsockopt",
};

/* ================================================================== */
/*  Hook 3: rtnl_fill_ifinfo — netlink RTM_NEWLINK (getifaddrs path)  */
/*  Android source path: net/core/rtnetlink.c                         */
/*                                                                    */
/*  rtnl_fill_ifinfo fills one interface's data into a netlink skb    */
/*  during a RTM_GETLINK dump. If the device is a VPN and the caller  */
/*  is a target UID, we hide the entry from the dump.                 */
/*                                                                    */
/*  We can't return -EMSGSIZE (causes infinite retry of the same      */
/*  entry on android14-6.1, hanging RTM_GETLINK dumps). Instead use   */
/*  the same skb_trim approach as inet6_fill_ifaddr below: save       */
/*  skb->len before the fill, trim back on return, return 0. The      */
/*  iterator then sees a successful entry of zero bytes and advances. */
/* ================================================================== */

struct rtnl_fill_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int rtnl_fill_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct rtnl_fill_data *data;
	struct net_device *dev;

	if (!is_hook_active(2))
		return 1;

	data = (void *)ri->data;
	data->should_filter = false;

	if (!is_target_uid()) {
		vpnhide_dbg("rtnl_fill_entry: uid=%u target=0\n",
			    from_kuid(&init_user_ns, current_uid()));
		return 0;
	}

	dev = (struct net_device *)regs->regs[1];
	rcu_read_lock();
	if (dev && is_vpn_ifname(dev->name)) {
		data->skb = (struct sk_buff *)regs->regs[0];
		data->saved_len = data->skb ? data->skb->len : 0;
		data->should_filter = true;
		vpnhide_dbg(
			"rtnl_fill_entry: uid=%u target=1 iface=%s -> filter\n",
			from_kuid(&init_user_ns, current_uid()), dev->name);
	} else {
		vpnhide_dbg(
			"rtnl_fill_entry: uid=%u target=1 iface=%s -> pass\n",
			from_kuid(&init_user_ns, current_uid()),
			dev ? dev->name : "(null)");
	}
	rcu_read_unlock();

	return 0;
}

static int rtnl_fill_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct rtnl_fill_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	vpnhide_dbg("rtnl_fill_ret: trimming skb %u -> %u\n", data->skb->len,
		    data->saved_len);
	record_kmod_intercept(from_kuid(&init_user_ns, current_uid()), 2);
	skb_trim(data->skb, data->saved_len);
	regs_set_return_value(regs, 0);
	return 0;
}

static struct kretprobe rtnl_fill_krp = {
	.handler = rtnl_fill_ret,
	.entry_handler = rtnl_fill_entry,
	.data_size = sizeof(struct rtnl_fill_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "rtnl_fill_ifinfo",
};

/* ================================================================== */
/*  Hook 4: inet6_fill_ifaddr — RTM_GETADDR IPv6 (getifaddrs path)    */
/*  Android source path: net/ipv6/addrconf.c                          */
/*                                                                    */
/*  inet6_fill_ifaddr(struct sk_buff *skb, struct inet6_ifaddr *ifa,  */
/*                    struct inet6_fill_args *args)                   */
/*  arm64: x0=skb, x1=ifa                                             */
/*                                                                    */
/*  getifaddrs() does RTM_GETLINK (filtered by hook 3) then           */
/*  RTM_GETADDR. Addresses for VPN interfaces still appear in         */
/*  RTM_GETADDR, so bionic reconstructs a tun0 entry with flags=0.    */
/*  Filtering here prevents that.                                     */
/*                                                                    */
/*  We can't return -EMSGSIZE (causes infinite retry on empty skb).   */
/*  Instead, save skb->len before and trim the skb back on return,    */
/*  making it look like the entry was never written. Return 0.        */
/* ================================================================== */

struct inet6_fill_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int inet6_fill_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct inet6_fill_data *data;
	struct inet6_ifaddr *ifa;

	if (!is_hook_active(3))
		return 1;

	data = (void *)ri->data;
	data->should_filter = false;

	if (!is_target_uid())
		return 0;

	ifa = (struct inet6_ifaddr *)regs->regs[1];
	rcu_read_lock();
	if (ifa && ifa->idev && ifa->idev->dev &&
	    is_vpn_ifname(ifa->idev->dev->name)) {
		data->skb = (struct sk_buff *)regs->regs[0];
		data->saved_len = data->skb ? data->skb->len : 0;
		data->should_filter = true;
		vpnhide_dbg("inet6_fill_entry: uid=%u iface=%s -> filter\n",
			    from_kuid(&init_user_ns, current_uid()),
			    ifa->idev->dev->name);
	}
	rcu_read_unlock();

	return 0;
}

static int inet6_fill_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct inet6_fill_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	vpnhide_dbg("inet6_fill_ret: trimming skb %u -> %u\n", data->skb->len,
		    data->saved_len);
	record_kmod_intercept(from_kuid(&init_user_ns, current_uid()), 2);
	skb_trim(data->skb, data->saved_len);
	regs_set_return_value(regs, 0);
	return 0;
}

static struct kretprobe inet6_fill_krp = {
	.handler = inet6_fill_ret,
	.entry_handler = inet6_fill_entry,
	.data_size = sizeof(struct inet6_fill_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "inet6_fill_ifaddr",
};

/* ================================================================== */
/*  Hook 5: inet_fill_ifaddr — RTM_GETADDR IPv4 (getifaddrs path)     */
/*  Android source path: net/ipv4/devinet.c                           */
/*                                                                    */
/*  inet_fill_ifaddr(struct sk_buff *skb, struct in_ifaddr *ifa,      */
/*                   struct inet_fill_args *args)                     */
/*  arm64: x0=skb, x1=ifa                                             */
/*  Same skb-trim approach as hook 4.                                 */
/* ================================================================== */

struct inet_fill_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int inet_fill_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct inet_fill_data *data;
	struct in_ifaddr *ifa;

	if (!is_hook_active(4))
		return 1;

	data = (void *)ri->data;
	data->should_filter = false;

	if (!is_target_uid())
		return 0;

	ifa = (struct in_ifaddr *)regs->regs[1];
	rcu_read_lock();
	if (ifa && ifa->ifa_dev && ifa->ifa_dev->dev &&
	    is_vpn_ifname(ifa->ifa_dev->dev->name)) {
		data->skb = (struct sk_buff *)regs->regs[0];
		data->saved_len = data->skb ? data->skb->len : 0;
		data->should_filter = true;
		vpnhide_dbg("inet_fill_entry: uid=%u iface=%s -> filter\n",
			    from_kuid(&init_user_ns, current_uid()),
			    ifa->ifa_dev->dev->name);
	}
	rcu_read_unlock();

	return 0;
}

static int inet_fill_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct inet_fill_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	vpnhide_dbg("inet_fill_ret: trimming skb %u -> %u\n", data->skb->len,
		    data->saved_len);
	record_kmod_intercept(from_kuid(&init_user_ns, current_uid()), 2);
	skb_trim(data->skb, data->saved_len);
	regs_set_return_value(regs, 0);
	return 0;
}

static struct kretprobe inet_fill_krp = {
	.handler = inet_fill_ret,
	.entry_handler = inet_fill_entry,
	.data_size = sizeof(struct inet_fill_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "inet_fill_ifaddr",
};

/* ================================================================== */
/*  Hook 6: fib_route_seq_show — /proc/net/route                      */
/*  Android source path: net/ipv4/fib_trie.c                          */
/*                                                                    */
/*  fib_route_seq_show(struct seq_file *seq, void *v) writes one or   */
/*  more tab-separated route lines into seq->buf, each ending with    */
/*  '\n'. The first field is the interface name.                      */
/*                                                                    */
/*  We save seq and seq->count on entry. In the return handler we     */
/*  scan what was written, compact out VPN lines, and adjust count.   */
/* ================================================================== */

struct fib_route_data {
	struct seq_file *seq;
	size_t start_count;
	bool target;
};

static int fib_route_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data;
	if (!is_hook_active(5))
		return 1;
	data = (void *)ri->data;

	data->seq = (struct seq_file *)regs->regs[0];
	data->target = is_target_uid();

	if (data->target && data->seq) {
		data->start_count = data->seq->count;
		vpnhide_dbg("fib_route_entry: uid=%u target=1\n",
			    from_kuid(&init_user_ns, current_uid()));
	} else {
		data->start_count = 0;
	}

	return 0;
}

static int fib_route_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data = (void *)ri->data;
	struct seq_file *seq = data->seq;
	char *buf, *src, *dst, *end;
	char ifname[IFNAMSIZ];
	int j;

	if (!data->target || !seq || !seq->buf)
		return 0;

	if (seq->count <= data->start_count)
		return 0;

	buf = seq->buf;
	src = buf + data->start_count;
	dst = src;
	end = buf + seq->count;

	while (src < end) {
		char *nl = memchr(src, '\n', end - src);
		char *line_end = nl ? nl + 1 : end;
		size_t line_len = line_end - src;

		for (j = 0; j < IFNAMSIZ - 1 && j < (int)line_len &&
			    src[j] != '\t' && src[j] != '\n';
		     j++)
			ifname[j] = src[j];
		ifname[j] = '\0';

		if (is_vpn_ifname(ifname)) {
			vpnhide_dbg("fib_route_ret: hiding route for %s\n",
				    ifname);
			record_kmod_intercept(
				from_kuid(&init_user_ns, current_uid()), 2);
			src = line_end;
			continue;
		}

		if (dst != src)
			memmove(dst, src, line_len);
		dst += line_len;
		src = line_end;
	}

	seq->count = dst - buf;
	return 0;
}

static struct kretprobe fib_route_krp = {
	.handler = fib_route_ret,
	.entry_handler = fib_route_entry,
	.data_size = sizeof(struct fib_route_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "fib_route_seq_show",
};

/* ================================================================== */
/*  Hook 7: fib_dump_info — IPv4 routes dump                          */
/*  Android source path: net/ipv4/fib_semantics.c                     */
/*                                                                    */
/*  fib_dump_info(skb, portid, seq, event, fri, flags)                */
/*  arm64: x0=skb, x4=fri (struct fib_rt_info*)                       */
/* ================================================================== */

static struct net_device *vpnhide_get_fib_info_dev(struct fib_info *fi)
{
	struct net_device *dev = NULL;

	if (!fi)
		return NULL;

	rcu_read_lock();
	{
		struct nexthop *nh = NULL;
		if (copy_from_kernel_nofault(&nh, &fi->nh, sizeof(nh)) == 0 &&
		    nh) {
			/* Route uses nexthop objects */
			bool is_group = false;
			copy_from_kernel_nofault(&is_group, &nh->is_group,
						 sizeof(is_group));
			if (is_group) {
				struct nh_group *nh_grp = NULL;
				if (copy_from_kernel_nofault(
					    &nh_grp, &nh->nh_grp,
					    sizeof(nh_grp)) == 0 &&
				    nh_grp) {
					u16 num_nh = 0;
					copy_from_kernel_nofault(
						&num_nh, &nh_grp->num_nh,
						sizeof(num_nh));
					if (num_nh > 0) {
						struct nexthop *nhe = NULL;
						if (copy_from_kernel_nofault(
							    &nhe,
							    &nh_grp->nh_entries[0]
								     .nh,
							    sizeof(nhe)) == 0 &&
						    nhe) {
							struct nh_info *nhi =
								NULL;
							if (copy_from_kernel_nofault(
								    &nhi,
								    &nhe->nh_info,
								    sizeof(nhi)) ==
								    0 &&
							    nhi) {
								copy_from_kernel_nofault(
									&dev,
									&nhi->fib_nhc
										 .nhc_dev,
									sizeof(dev));
							}
						}
					}
				}
			} else {
				struct nh_info *nhi = NULL;
				if (copy_from_kernel_nofault(&nhi, &nh->nh_info,
							     sizeof(nhi)) ==
					    0 &&
				    nhi) {
					copy_from_kernel_nofault(
						&dev, &nhi->fib_nhc.nhc_dev,
						sizeof(dev));
				}
			}
		} else {
			/* Traditional fib_nh array */
			int fib_nhs = 0;
			copy_from_kernel_nofault(&fib_nhs, &fi->fib_nhs,
						 sizeof(fib_nhs));
			if (fib_nhs > 0) {
				copy_from_kernel_nofault(
					&dev, &fi->fib_nh[0].nh_common.nhc_dev,
					sizeof(dev));
			}
		}
	}
	rcu_read_unlock();

	return dev;
}

struct fib_dump_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int fib_dump_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_dump_data *data;
	struct fib_info *fi = NULL;
	struct fib_rt_info *fri;
	struct fib_rt_info fri_copy;

	if (!is_hook_active(7))
		return 1;

	data = (void *)ri->data;
	data->should_filter = false;

	if (!is_target_uid())
		return 0;

	data->skb = (struct sk_buff *)regs->regs[0];

	/* GKI 5.10 and 5.15+ both pass struct fib_rt_info* in x4 (regs->regs[4]) */
	fri = (struct fib_rt_info *)regs->regs[4];
	if (fri &&
	    copy_from_kernel_nofault(&fri_copy, fri, sizeof(fri_copy)) == 0) {
		fi = fri_copy.fi;
	}

	rcu_read_lock();
	if (fi) {
		struct net_device *dev = vpnhide_get_fib_info_dev(fi);
		if (dev && is_vpn_ifname(dev->name)) {
			data->saved_len = data->skb ? data->skb->len : 0;
			data->should_filter = true;
			vpnhide_dbg("fib_dump_entry: hiding route via %s\n",
				    dev->name);
		}
	}
	rcu_read_unlock();

	return 0;
}

static int fib_dump_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_dump_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	if (regs_return_value(regs) >= 0) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      2);
		skb_trim(data->skb, data->saved_len);
		regs_set_return_value(regs, 0);
	}
	return 0;
}

static struct kretprobe fib_dump_krp = {
	.handler = fib_dump_ret,
	.entry_handler = fib_dump_entry,
	.data_size = sizeof(struct fib_dump_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "fib_dump_info",
};

/* ================================================================== */
/*  Hook 7b: fib_nl_fill_rule — policy routing rules (RTM_GETRULE)    */
/*  Android source path: net/core/fib_rules.c                         */
/*                                                                    */
/*  fib_nl_fill_rule(skb, rule, pid, seq, type, flags, ops)           */
/*  arm64: x0=skb, x1=rule (struct fib_rule*)                         */
/* ================================================================== */

struct fib_rule_dump_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int fib_rule_fill_entry(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	struct fib_rule_dump_data *data;
	struct fib_rule *rule;
	uid_t my_uid;

	if (!is_hook_active(8))
		return 1;

	data = (void *)ri->data;
	data->should_filter = false;

	if (!is_target_uid())
		return 0;

	rule = (struct fib_rule *)regs->regs[1];
	if (!rule)
		return 0;

	data->skb = (struct sk_buff *)regs->regs[0];
	my_uid = from_kuid(&init_user_ns, current_uid());

	rcu_read_lock();
	if ((rule->iifname[0] != '\0' && is_vpn_ifname(rule->iifname)) ||
	    (rule->oifname[0] != '\0' && is_vpn_ifname(rule->oifname))) {
		data->saved_len = data->skb ? data->skb->len : 0;
		data->should_filter = true;
		vpnhide_dbg(
			"fib_rule_fill_entry: hiding rule via VPN interface %s / %s\n",
			rule->iifname, rule->oifname);
	} else {
		uid_t start = from_kuid(&init_user_ns, rule->uid_range.start);
		uid_t end = from_kuid(&init_user_ns, rule->uid_range.end);
		if (my_uid >= start && my_uid <= end) {
			if (start != 0 || end != (uid_t)~0) {
				if (rule->table != 254 && rule->table != 255 &&
				    rule->table != 253 && rule->table > 100) {
					data->saved_len =
						data->skb ? data->skb->len : 0;
					data->should_filter = true;
					vpnhide_dbg(
						"fib_rule_fill_entry: hiding policy rule for UID range %u-%u, table %u\n",
						start, end, rule->table);
				}
			}
		}
	}
	rcu_read_unlock();

	return 0;
}

static int fib_rule_fill_ret(struct kretprobe_instance *ri,
			     struct pt_regs *regs)
{
	struct fib_rule_dump_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	if (regs_return_value(regs) >= 0) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      2);
		/* Trim the Netlink buffer back to remove the serialized rule */
		skb_trim(data->skb, data->saved_len);
		regs_set_return_value(regs, 0);
	}
	return 0;
}

static struct kretprobe fib_rule_fill_krp = {
	.handler = fib_rule_fill_ret,
	.entry_handler = fib_rule_fill_entry,
	.data_size = sizeof(struct fib_rule_dump_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "fib_nl_fill_rule",
};

/* ================================================================== */
/*  Hook 8: rt6_fill_node — IPv6 routes                               */
/*  Android source path: net/ipv6/route.c                             */
/*                                                                    */
/*  rt6_fill_node(net, skb, rt, dst, ...)                             */
/*  arm64: x1=skb, x3=dst (struct dst_entry*)                         */
/* ================================================================== */

struct rt6_fill_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int rt6_fill_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct rt6_fill_data *data;
	struct fib6_info *rt;
	struct dst_entry *dst;

	if (!is_hook_active(9))
		return 1;

	data = (void *)ri->data;
	data->should_filter = false;

	if (!is_target_uid())
		return 0;

	data->skb = (struct sk_buff *)regs->regs[1];
	rt = (struct fib6_info *)regs->regs[2];
	dst = (struct dst_entry *)regs->regs[3];

	rcu_read_lock();
	if (rt) {
		struct net_device *dev = rt->fib6_nh->nh_common.nhc_dev;
		if (dev && is_vpn_ifname(dev->name)) {
			data->saved_len = data->skb ? data->skb->len : 0;
			data->should_filter = true;
			vpnhide_dbg(
				"rt6_fill_entry: hiding IPv6 route via %s (rt)\n",
				dev->name);
		}
	} else if (dst && dst->dev && is_vpn_ifname(dst->dev->name)) {
		data->saved_len = data->skb ? data->skb->len : 0;
		data->should_filter = true;
		vpnhide_dbg("rt6_fill_entry: hiding IPv6 route via %s (dst)\n",
			    dst->dev->name);
	}
	rcu_read_unlock();

	return 0;
}

static int rt6_fill_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct rt6_fill_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	if (regs_return_value(regs) >= 0) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      2);
		skb_trim(data->skb, data->saved_len);
		regs_set_return_value(regs, 0);
	}
	return 0;
}

static struct kretprobe rt6_fill_krp = {
	.handler = rt6_fill_ret,
	.entry_handler = rt6_fill_entry,
	.data_size = sizeof(struct rt6_fill_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "rt6_fill_node",
};

/* ================================================================== */
/*  Hook 8b: ipv6_route_seq_show — /proc/net/ipv6_route               */
/*  Android source path: net/ipv6/route.c                             */
/*                                                                    */
/*  ipv6_route_seq_show(seq, v) is the IPv6 equivalent of hook 6.     */
/*  The interface name is the LAST field in the line.                 */
/* ================================================================== */

static int ipv6_route_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data;
	if (!is_hook_active(6))
		return 1;
	data = (void *)ri->data;

	data->seq = (struct seq_file *)regs->regs[0];
	data->target = is_target_uid();

	if (data->target && data->seq) {
		data->start_count = data->seq->count;
		vpnhide_dbg("ipv6_route_entry: uid=%u target=1\n",
			    from_kuid(&init_user_ns, current_uid()));
	} else {
		data->start_count = 0;
	}

	return 0;
}

static int ipv6_route_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data = (void *)ri->data;
	struct seq_file *seq = data->seq;
	char *buf, *src, *dst, *end;
	char ifname[IFNAMSIZ];
	int j;

	if (!data->target || !seq || !seq->buf)
		return 0;

	if (seq->count <= data->start_count)
		return 0;

	buf = seq->buf;
	src = buf + data->start_count;
	dst = src;
	end = buf + seq->count;

	while (src < end) {
		char *nl = memchr(src, '\n', end - src);
		char *line_end = nl ? nl + 1 : end;
		size_t line_len = line_end - src;
		char *p;

		p = line_end - 1;
		while (p >= src &&
		       (*p == '\n' || *p == '\r' || *p == ' ' || *p == '\t'))
			p--;

		j = 0;
		while (p >= src && *p != ' ' && *p != '\t' &&
		       j < IFNAMSIZ - 1) {
			j++;
			p--;
		}
		p++;

		for (j = 0; j < IFNAMSIZ - 1 && (p + j) < line_end &&
			    p[j] != ' ' && p[j] != '\t' && p[j] != '\n';
		     j++)
			ifname[j] = p[j];
		ifname[j] = '\0';

		if (is_vpn_ifname(ifname)) {
			vpnhide_dbg(
				"ipv6_route_ret: hiding IPv6 route for %s\n",
				ifname);
			record_kmod_intercept(
				from_kuid(&init_user_ns, current_uid()), 2);
			src = line_end;
			continue;
		}

		if (dst != src)
			memmove(dst, src, line_len);
		dst += line_len;
		src = line_end;
	}

	seq->count = dst - buf;
	return 0;
}

static struct kretprobe ipv6_route_krp = {
	.handler = ipv6_route_ret,
	.entry_handler = ipv6_route_entry,
	.data_size = sizeof(struct fib_route_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "ipv6_route_seq_show",
};

/* ================================================================== */
/*  Hook 9: rt_fill_info — IPv4 single route lookup                   */
/*  Android source path: net/ipv4/route.c                             */
/*                                                                    */
/*  6.6: rt_fill_info(net, dst, src, rt, table_id, fl4, skb, ...)     */
/*  arm64: x0=net, x3=rt (struct rtable*), x6=skb                     */
/* ================================================================== */

struct rt_fill_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int rt_fill_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct rt_fill_data *data;
	struct net_device *dev = NULL;
	struct rtable *rt = NULL;
	struct sk_buff *skb = NULL;
	struct net_device *dev_ptr = NULL;
	unsigned int temp_len = 0;
	char ifname[IFNAMSIZ];

	if (!is_hook_active(10))
		return 1;

	data = (void *)ri->data;
	data->should_filter = false;
	data->skb = NULL;
	data->saved_len = 0;

	if (!is_target_uid())
		return 0;

	rt = (struct rtable *)regs->regs[3];
	skb = (struct sk_buff *)regs->regs[7];

	if (rt) {
		if (copy_from_kernel_nofault(&dev_ptr, &rt->dst.dev,
					     sizeof(dev_ptr)) == 0 &&
		    dev_ptr) {
			memset(ifname, 0, sizeof(ifname));
			if (copy_from_kernel_nofault(ifname, dev_ptr->name,
						     IFNAMSIZ - 1) == 0) {
				ifname[IFNAMSIZ - 1] = '\0';
				dev = dev_ptr;
			}
		}
	}

	if (skb) {
		if (copy_from_kernel_nofault(&temp_len, &skb->len,
					     sizeof(temp_len)) == 0) {
			data->skb = skb;
			data->saved_len = temp_len;
		}
	}

	rcu_read_lock();
	if (dev && is_vpn_ifname(ifname)) {
		data->should_filter = true;
		vpnhide_dbg("rt_fill_entry: hiding route via %s\n", ifname);
	}
	rcu_read_unlock();

	return 0;
}

static int rt_fill_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct rt_fill_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	if (regs_return_value(regs) >= 0) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      2);
		skb_trim(data->skb, data->saved_len);
		regs_set_return_value(regs, 0);
	}
	return 0;
}

static struct kretprobe rt_fill_krp = {
	.handler = rt_fill_ret,
	.entry_handler = rt_fill_entry,
	.data_size = sizeof(struct rt_fill_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "rt_fill_info",
};

/* ================================================================== */
/*  UID List Update Logic                                             */
/* ================================================================== */

static int update_port_rules(struct vpnhide_uid_port_rules *rules, int count)
{
	struct vpnhide_port_targets *new_t, *old_t;

	new_t = kvzalloc(sizeof(*new_t), GFP_KERNEL);
	if (!new_t)
		return -ENOMEM;

	new_t->count = count;
	if (count > 0)
		memcpy(new_t->targets, rules,
		       count * sizeof(struct vpnhide_uid_port_rules));

	spin_lock(&port_targets_update_lock);
	old_t = rcu_dereference_protected(
		global_port_targets,
		lockdep_is_held(&port_targets_update_lock));
	rcu_assign_pointer(global_port_targets, new_t);
	spin_unlock(&port_targets_update_lock);

	if (old_t) {
		synchronize_rcu();
		kvfree(old_t);
	}

	vpnhide_dbg("Port rules updated: %d UIDs\n", count);
	return 0;
}

static int update_targets(uid_t *uids, int count)
{
	struct vpnhide_targets *new_t, *old_t;

	new_t = kzalloc(sizeof(*new_t), GFP_KERNEL);
	if (!new_t)
		return -ENOMEM;

	new_t->count = count;
	if (count > 0)
		memcpy(new_t->uids, uids, count * sizeof(uid_t));

	spin_lock(&targets_update_lock);
	old_t = rcu_dereference_protected(
		global_targets, lockdep_is_held(&targets_update_lock));
	rcu_assign_pointer(global_targets, new_t);
	spin_unlock(&targets_update_lock);

	if (old_t) {
		synchronize_rcu();
		kfree(old_t);
	}

	vpnhide_dbg("Normal targets updated: %d UIDs\n", count);
	return 0;
}

static int handle_vpnhide_ioctl(unsigned int cmd, unsigned long arg)
{
	struct vpnhide_ioctl_data *kdata;
	int val, ret = 0;

	if (!capable(CAP_NET_ADMIN))
		return -EPERM;

	switch (cmd) {
	case VH_SET_TARGETS:
	case VH_SET_PORT_TARGETS:
		kdata = kmalloc(sizeof(*kdata), GFP_KERNEL);
		if (!kdata)
			return -ENOMEM;

		if (copy_from_user(kdata, (void __user *)arg, sizeof(*kdata))) {
			kfree(kdata);
			return -EFAULT;
		}

		if (kdata->count < 0 || kdata->count > MAX_TARGET_UIDS) {
			kfree(kdata);
			return -EINVAL;
		}

		if (cmd == VH_SET_TARGETS)
			ret = update_targets(kdata->uids, kdata->count);
		else {
			struct vpnhide_uid_port_rules *rules;
			rules = kvmalloc_array(kdata->count, sizeof(*rules),
					       GFP_KERNEL);
			if (rules) {
				int i;
				for (i = 0; i < kdata->count; i++) {
					rules[i].uid = kdata->uids[i];
					rules[i].rule_count = 1;
					rules[i].rules[0].start_port = 0;
					rules[i].rules[0].end_port = 65535;
					rules[i].rules[0].protocol =
						VH_PROTO_BOTH;
				}
				ret = update_port_rules(rules, kdata->count);
				kvfree(rules);
			} else {
				ret = -ENOMEM;
			}
		}

		kfree(kdata);
		break;

	case VH_GET_TARGETS: {
		struct vpnhide_targets *t;
		struct vpnhide_ioctl_data *kdata;

		kdata = kzalloc(sizeof(*kdata), GFP_KERNEL);
		if (!kdata)
			return -ENOMEM;

		rcu_read_lock();
		t = rcu_dereference(global_targets);
		if (t) {
			kdata->count = t->count;
			memcpy(kdata->uids, t->uids, sizeof(t->uids));
		}
		rcu_read_unlock();

		if (copy_to_user((void __user *)arg, kdata, sizeof(*kdata))) {
			kfree(kdata);
			return -EFAULT;
		}

		kfree(kdata);
		break;
	}

	case VH_SET_PORT_RULES: {
		struct vpnhide_port_ioctl_data *pdata;
		pdata = kvzalloc(sizeof(*pdata), GFP_KERNEL);
		if (!pdata)
			return -ENOMEM;

		if (copy_from_user(pdata, (void __user *)arg, sizeof(*pdata))) {
			kvfree(pdata);
			return -EFAULT;
		}

		if (pdata->count < 0 || pdata->count > MAX_TARGET_UIDS) {
			kvfree(pdata);
			return -EINVAL;
		}

		ret = update_port_rules(pdata->targets, pdata->count);
		kvfree(pdata);
		break;
	}

	case VH_SET_DEBUG:
		if (get_user(val, (int __user *)arg))
			return -EFAULT;
		WRITE_ONCE(debug_enabled, !!val);
		vpnhide_dbg("debug logging %s\n",
			    READ_ONCE(debug_enabled) ? "enabled" : "disabled");
		break;

	case VH_SET_IFACE_PREFIXES: {
		struct vpnhide_iface_ioctl_data *idata;
		struct vpnhide_iface_prefixes *new_p, *old_p;

		idata = kmalloc(sizeof(*idata), GFP_KERNEL);
		if (!idata)
			return -ENOMEM;

		if (copy_from_user(idata, (void __user *)arg, sizeof(*idata))) {
			kfree(idata);
			return -EFAULT;
		}

		if (idata->count < 0 || idata->count > MAX_IFACE_PREFIXES) {
			kfree(idata);
			return -EINVAL;
		}

		new_p = kzalloc(sizeof(*new_p), GFP_KERNEL);
		if (!new_p) {
			kfree(idata);
			return -ENOMEM;
		}

		new_p->count = idata->count;
		memcpy(new_p->prefixes, idata->prefixes,
		       sizeof(new_p->prefixes));

		mutex_lock(&iface_prefixes_lock);
		old_p = rcu_dereference_protected(
			global_iface_prefixes,
			lockdep_is_held(&iface_prefixes_lock));
		rcu_assign_pointer(global_iface_prefixes, new_p);
		mutex_unlock(&iface_prefixes_lock);

		if (old_p) {
			synchronize_rcu();
			kfree(old_p);
		}

		kfree(idata);
		ret = 0;
		break;
	}

	case VH_SET_SPOOF_IP: {
		struct vpnhide_spoof_ip sip;
		if (copy_from_user(&sip, (void __user *)arg, sizeof(sip)))
			return -EFAULT;
		spin_lock(&spoof_ip_lock);
		global_spoof_ip = sip;
		spin_unlock(&spoof_ip_lock);
		vpnhide_dbg(
			"ioctl: updated spoof IP: IPv4=%pI4 (%d), IPv6=%pI6c (%d)\n",
			&sip.ipv4_addr, sip.has_ipv4, sip.ipv6_addr,
			sip.has_ipv6);
		ret = 0;
		break;
	}

	case VH_SET_ACTIVE_HOOKS:
		if (get_user(val, (unsigned int __user *)arg))
			return -EFAULT;
		WRITE_ONCE(active_hooks_mask, val);
		vpnhide_dbg("active hooks mask updated: 0x%X\n", val);
		ret = 0;
		break;

	case VH_SET_COVER_IFACE: {
		struct vpnhide_cover_iface ci;
		if (copy_from_user(&ci, (void __user *)arg, sizeof(ci)))
			return -EFAULT;
		atomic_set(&global_cover_ifindex, (int)ci.ifindex);
		vpnhide_dbg("ioctl: cover ifindex set to %u\n", ci.ifindex);
		ret = 0;
		break;
	}

	case VH_GET_ACTIVE_HOOKS:
		val = READ_ONCE(active_hooks_mask);
		if (put_user(val, (unsigned int __user *)arg))
			return -EFAULT;
		ret = 0;
		break;

	case VH_GET_STATS: {
		struct vpnhide_kmod_stats_data *sdata;
		unsigned long flags;
		u64 now_min = ktime_get_real_seconds() / 60;
		int i, b, active_count = 0;

		sdata = kvzalloc(sizeof(*sdata), GFP_KERNEL);
		if (!sdata)
			return -ENOMEM;

		spin_lock_irqsave(&kmod_stats_lock, flags);
		for (i = 0; i < kmod_stats_count; i++) {
			u32 ioctl_sum = 0, netlink_sum = 0, connect_sum = 0,
			    getname_sum = 0;
			for (b = 0; b < BUCKETS_COUNT; b++) {
				if (now_min - kmod_stats[i].bucket_times[b] <
				    BUCKETS_COUNT) {
					ioctl_sum +=
						kmod_stats[i].ioctl_counts[b];
					netlink_sum +=
						kmod_stats[i].netlink_counts[b];
					connect_sum +=
						kmod_stats[i].connect_counts[b];
					getname_sum +=
						kmod_stats[i].getname_counts[b];
				}
			}
			if (ioctl_sum > 0 || netlink_sum > 0 ||
			    connect_sum > 0 || getname_sum > 0) {
				sdata->stats[active_count].uid =
					kmod_stats[i].uid;
				sdata->stats[active_count].ioctl_count =
					ioctl_sum;
				sdata->stats[active_count].netlink_count =
					netlink_sum;
				sdata->stats[active_count].connect_count =
					connect_sum;
				sdata->stats[active_count].getname_count =
					getname_sum;
				active_count++;
			}
		}
		sdata->count = active_count;
		spin_unlock_irqrestore(&kmod_stats_lock, flags);

		if (copy_to_user((void __user *)arg, sdata, sizeof(*sdata))) {
			kvfree(sdata);
			return -EFAULT;
		}
		kvfree(sdata);
		ret = 0;
		break;
	}
	case VH_CLEAR_STATS: {
		unsigned long flags;
		spin_lock_irqsave(&kmod_stats_lock, flags);
		kmod_stats_count = 0;
		memset(kmod_stats, 0, sizeof(kmod_stats));
		spin_unlock_irqrestore(&kmod_stats_lock, flags);
		ret = 0;
		break;
	}

	default:
		return -ENOIOCTLCMD;
	}

	return ret;
}

static long vpnhide_dev_ioctl(struct file *file, unsigned int cmd,
			      unsigned long arg)
{
	return handle_vpnhide_ioctl(cmd, arg);
}

static const struct file_operations vpnhide_fops = {
	.owner = THIS_MODULE,
	.unlocked_ioctl = vpnhide_dev_ioctl,
#ifdef CONFIG_COMPAT
	.compat_ioctl = vpnhide_dev_ioctl,
#endif
};

static struct miscdevice vpnhide_misc = {
	.minor = MISC_DYNAMIC_MINOR,
	.name = "vpnhide_ctrl",
	.fops = &vpnhide_fops,
	.mode = 0660,
};

/* ================================================================== */
/*  Hook 12: security_socket_connect — Port Hiding                    */
/*  Android source path: security/security.c                          */
/*                                                                    */
/*  security_socket_connect(struct socket *sock,                      */
/*                          struct sockaddr *address, int addrlen)    */
/*  arm64: x1=address                                                 */
/*                                                                    */
/*  If a target app tries to connect to 127.0.0.1 or ::1, we return   */
/*  -ECONNREFUSED. This covers all protocols (TCP, UDP, etc.)         */
/* ================================================================== */

struct socket_connect_data {
	bool should_block;
};

static int socket_connect_entry(struct kretprobe_instance *ri,
				struct pt_regs *regs)
{
	struct socket_connect_data *data;
	struct socket *sock = (struct socket *)regs->regs[0];
	struct sockaddr *addr = (struct sockaddr *)regs->regs[1];
	uid_t uid = from_kuid(&init_user_ns, current_uid());
	struct vpnhide_port_targets *t;
	struct vpnhide_uid_port_rules *urules = NULL;
	int i;

	if (!is_hook_active(13))
		return 1;

	data = (void *)ri->data;
	data->should_block = false;

	rcu_read_lock();
	t = rcu_dereference(global_port_targets);
	if (t) {
		for (i = 0; i < t->count; i++) {
			if (t->targets[i].uid == uid) {
				urules = &t->targets[i];
				break;
			}
		}
	}

	if (!urules || !addr || !sock || !sock->sk) {
		rcu_read_unlock();
		return 0;
	}

	if (addr->sa_family == AF_INET) {
		struct sockaddr_in *sin = (struct sockaddr_in *)addr;
		if (ipv4_is_loopback(sin->sin_addr.s_addr) ||
		    sin->sin_addr.s_addr == htonl(INADDR_ANY)) {
			unsigned short port = ntohs(sin->sin_port);
			unsigned char proto =
				(sock->sk->sk_type == SOCK_STREAM) ?
					VH_PROTO_TCP :
					VH_PROTO_UDP;

			for (i = 0; i < urules->rule_count; i++) {
				struct vpnhide_port_rule *r = &urules->rules[i];
				if (port >= r->start_port &&
				    port <= r->end_port) {
					if (r->protocol == VH_PROTO_BOTH ||
					    r->protocol == proto) {
						data->should_block = true;
						vpnhide_dbg(
							"socket_connect: blocking IPv4 port %u (%s) for uid=%u\n",
							port,
							(proto ==
							 VH_PROTO_TCP) ?
								"TCP" :
								"UDP",
							uid);
						break;
					}
				}
			}
		}
	} else if (addr->sa_family == AF_INET6) {
		struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)addr;
		bool is_loopback = false;

		if (ipv6_addr_loopback(&sin6->sin6_addr) ||
		    ipv6_addr_any(&sin6->sin6_addr)) {
			is_loopback = true;
		} else if (ipv6_addr_v4mapped(&sin6->sin6_addr)) {
			__be32 v4addr = sin6->sin6_addr.s6_addr32[3];
			if (ipv4_is_loopback(v4addr) ||
			    v4addr == htonl(INADDR_ANY)) {
				is_loopback = true;
			}
		}

		if (is_loopback) {
			unsigned short port = ntohs(sin6->sin6_port);
			unsigned char proto =
				(sock->sk->sk_type == SOCK_STREAM) ?
					VH_PROTO_TCP :
					VH_PROTO_UDP;

			for (i = 0; i < urules->rule_count; i++) {
				struct vpnhide_port_rule *r = &urules->rules[i];
				if (port >= r->start_port &&
				    port <= r->end_port) {
					if (r->protocol == VH_PROTO_BOTH ||
					    r->protocol == proto) {
						data->should_block = true;
						vpnhide_dbg(
							"socket_connect: blocking IPv6 port %u (%s) for uid=%u\n",
							port,
							(proto ==
							 VH_PROTO_TCP) ?
								"TCP" :
								"UDP",
							uid);
						break;
					}
				}
			}
		}
	}
	rcu_read_unlock();

	return 0;
}

static int socket_connect_ret(struct kretprobe_instance *ri,
			      struct pt_regs *regs)
{
	struct socket_connect_data *data = (void *)ri->data;

	if (data->should_block) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      3);
		regs_set_return_value(regs, -ECONNREFUSED);
	}

	return 0;
}

static struct kretprobe socket_connect_krp = {
	.handler = socket_connect_ret,
	.entry_handler = socket_connect_entry,
	.data_size = sizeof(struct socket_connect_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "security_socket_connect",
};

/* ================================================================== */
/*  Hook 12b: security_socket_bind — Loopback Port Bind Spoofing      */
/*  Android source path: security/security.c                          */
/*                                                                    */
/*  security_socket_bind(struct socket *sock,                         */
/*                       struct sockaddr *address, int addrlen)       */
/*  arm64: x1=address                                                 */
/*                                                                    */
/*  If a target app tries to bind to 127.0.0.1 or ::1 on a protected  */
/*  port, we silently rewrite the port to 0 (ephemeral). The kernel   */
/*  will choose a free random port, bind succeeds, and return 0.       */
/* ================================================================== */

static int socket_bind_entry(struct kretprobe_instance *ri,
			     struct pt_regs *regs)
{
	struct socket *sock = (struct socket *)regs->regs[0];
	struct sockaddr *addr = (struct sockaddr *)regs->regs[1];
	uid_t uid = from_kuid(&init_user_ns, current_uid());
	struct vpnhide_port_targets *t;
	struct vpnhide_uid_port_rules *urules = NULL;
	int i;

	if (!is_hook_active(16))
		return 1;

	rcu_read_lock();
	t = rcu_dereference(global_port_targets);
	if (t) {
		for (i = 0; i < t->count; i++) {
			if (t->targets[i].uid == uid) {
				urules = &t->targets[i];
				break;
			}
		}
	}

	if (!urules || !addr || !sock || !sock->sk) {
		rcu_read_unlock();
		return 0;
	}

	if (addr->sa_family == AF_INET) {
		struct sockaddr_in *sin = (struct sockaddr_in *)addr;
		if (ipv4_is_loopback(sin->sin_addr.s_addr) ||
		    sin->sin_addr.s_addr == htonl(INADDR_ANY)) {
			unsigned short port = ntohs(sin->sin_port);
			unsigned char proto =
				(sock->sk->sk_type == SOCK_STREAM) ?
					VH_PROTO_TCP :
					VH_PROTO_UDP;

			for (i = 0; i < urules->rule_count; i++) {
				struct vpnhide_port_rule *r = &urules->rules[i];
				if (port >= r->start_port &&
				    port <= r->end_port) {
					if (r->protocol == VH_PROTO_BOTH ||
					    r->protocol == proto) {
						sin->sin_port = 0;
						vpnhide_dbg(
							"socket_bind: redirected IPv4 port %u to 0 for uid=%u\n",
							port, uid);
						break;
					}
				}
			}
		}
	} else if (addr->sa_family == AF_INET6) {
		struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)addr;
		bool is_loopback = false;

		if (ipv6_addr_loopback(&sin6->sin6_addr) ||
		    ipv6_addr_any(&sin6->sin6_addr)) {
			is_loopback = true;
		} else if (ipv6_addr_v4mapped(&sin6->sin6_addr)) {
			__be32 v4addr = sin6->sin6_addr.s6_addr32[3];
			if (ipv4_is_loopback(v4addr) ||
			    v4addr == htonl(INADDR_ANY)) {
				is_loopback = true;
			}
		}

		if (is_loopback) {
			unsigned short port = ntohs(sin6->sin6_port);
			unsigned char proto =
				(sock->sk->sk_type == SOCK_STREAM) ?
					VH_PROTO_TCP :
					VH_PROTO_UDP;

			for (i = 0; i < urules->rule_count; i++) {
				struct vpnhide_port_rule *r = &urules->rules[i];
				if (port >= r->start_port &&
				    port <= r->end_port) {
					if (r->protocol == VH_PROTO_BOTH ||
					    r->protocol == proto) {
						sin6->sin6_port = 0;
						vpnhide_dbg(
							"socket_bind: redirected IPv6 port %u to 0 for uid=%u\n",
							port, uid);
						break;
					}
				}
			}
		}
	}
	rcu_read_unlock();

	return 0;
}

static int socket_bind_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	return 0;
}

static struct kretprobe socket_bind_krp = {
	.handler = socket_bind_ret,
	.entry_handler = socket_bind_entry,
	.data_size = 0,
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "security_socket_bind",
};

/* ================================================================== */
/*  Hook 13: inet_getname & inet6_getname — getsockname Spoofing      */
/*  Android source path:                                              */
/*    - inet_getname: net/ipv4/af_inet.c                              */
/*    - inet6_getname: net/ipv6/af_inet6.c                            */
/* ================================================================== */

struct getname_data {
	struct sockaddr *uaddr;
	int peer;
};

static int inet_getname_entry(struct kretprobe_instance *ri,
			      struct pt_regs *regs)
{
	struct getname_data *data;
	int peer = (int)regs->regs[2];

	if (!is_hook_active(14))
		return 1;

	data = (void *)ri->data;

	if (peer == 0 && is_target_uid()) {
		data->uaddr = (struct sockaddr *)regs->regs[1];
		data->peer = peer;
	} else {
		data->uaddr = NULL;
	}
	return 0;
}

static int inet_getname_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct getname_data *data = (void *)ri->data;
	int retval = regs_return_value(regs);

	if (data->uaddr && retval >= 0) {
		struct sockaddr_in *sin = (struct sockaddr_in *)data->uaddr;
		struct vpnhide_spoof_ip sip;

		spin_lock(&spoof_ip_lock);
		sip = global_spoof_ip;
		spin_unlock(&spoof_ip_lock);

		if (sin->sin_family == AF_INET) {
			__be32 addr = sin->sin_addr.s_addr;
			if (addr != 0 &&
			    (ntohl(addr) & 0xFF000000) != 0x7F000000) {
				__be32 target_ip =
					sip.has_ipv4 ?
						sip.ipv4_addr :
						htonl(0xC0000004); /* 192.0.0.4 (CLAT default) */
				sin->sin_addr.s_addr = target_ip;
				record_kmod_intercept(from_kuid(&init_user_ns,
								current_uid()),
						      4);
				vpnhide_dbg(
					"inet_getname_ret: spoofed IPv4 from %pI4 to %pI4\n",
					&addr, &target_ip);
			}
		}
	}
	return 0;
}

static int inet6_getname_entry(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	struct getname_data *data;
	int peer = (int)regs->regs[2];

	if (!is_hook_active(15))
		return 1;

	data = (void *)ri->data;

	if (peer == 0 && is_target_uid()) {
		data->uaddr = (struct sockaddr *)regs->regs[1];
		data->peer = peer;
	} else {
		data->uaddr = NULL;
	}
	return 0;
}

static int inet6_getname_ret(struct kretprobe_instance *ri,
			     struct pt_regs *regs)
{
	struct getname_data *data = (void *)ri->data;
	int retval = regs_return_value(regs);

	if (data->uaddr && retval >= 0) {
		struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)data->uaddr;
		struct vpnhide_spoof_ip sip;

		spin_lock(&spoof_ip_lock);
		sip = global_spoof_ip;
		spin_unlock(&spoof_ip_lock);

		if (sin6->sin6_family == AF_INET6) {
			if (!ipv6_addr_any(&sin6->sin6_addr) &&
			    !ipv6_addr_loopback(&sin6->sin6_addr)) {
				struct in6_addr old_addr = sin6->sin6_addr;
				struct in6_addr target_ip6;

				if (sip.has_ipv6) {
					memcpy(&target_ip6, sip.ipv6_addr, 16);
				} else {
					/* Fallback to a mock global IPv6 address (e.g. 2001:db8::100) */
					memset(&target_ip6, 0, 16);
					target_ip6.s6_addr[0] = 0x20;
					target_ip6.s6_addr[1] = 0x01;
					target_ip6.s6_addr[2] = 0x0d;
					target_ip6.s6_addr[3] = 0xb8;
					target_ip6.s6_addr[15] = 0x10;
				}

				memcpy(&sin6->sin6_addr, &target_ip6, 16);
				record_kmod_intercept(from_kuid(&init_user_ns,
								current_uid()),
						      4);
				vpnhide_dbg(
					"inet6_getname_ret: spoofed IPv6 from %pI6c to %pI6c\n",
					&old_addr, &target_ip6);
			}
		}
	}
	return 0;
}

static struct kretprobe inet_getname_krp = {
	.handler = inet_getname_ret,
	.entry_handler = inet_getname_entry,
	.data_size = sizeof(struct getname_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "inet_getname",
};

static struct kretprobe inet6_getname_krp = {
	.handler = inet6_getname_ret,
	.entry_handler = inet6_getname_entry,
	.data_size = sizeof(struct getname_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "inet6_getname",
};

static int sock_ioctl_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct sock_ioctl_data *data;
	unsigned int cmd = (unsigned int)regs->regs[1];
	unsigned long arg = (unsigned long)regs->regs[2];

	if (!is_hook_active(1))
		return 1;

	data = (void *)ri->data;

	data->target = false;

	if (cmd != SIOCGIFCONF)
		return 0;
	if (!is_target_uid())
		return 0;

	data->target = true;
	data->argp = (void __user *)arg;
	vpnhide_dbg("sock_ioctl_entry: uid=%u SIOCGIFCONF argp=%px\n",
		    from_kuid(&init_user_ns, current_uid()), data->argp);
	return 0;
}

/* ================================================================== */
/*  eBPF Map Hijacking / Stats Hiding                                 */
/* ================================================================== */

static inline bool is_stats_or_uid_map(const char *name)
{
	if (!name)
		return false;
	return (strncmp(name, "app_uid_stats", 13) == 0 ||
		strncmp(name, "map_netd_app_ui", 15) == 0 ||
		strncmp(name, "stats_map_", 10) == 0 ||
		strncmp(name, "map_netd_stats", 14) == 0 ||
		strncmp(name, "iface_stats", 11) == 0 ||
		strncmp(name, "map_netd_iface_", 15) == 0 ||
		strncmp(name, "uid_stats", 9) == 0 ||
		strncmp(name, "map_netd_uid_st", 15) == 0 ||
		strncmp(name, "tether_stats", 12) == 0);
}

static bool is_key_vpn_or_target_uid(struct bpf_map *map, void *key)
{
	if (!map || !key)
		return false;

	if (strncmp(map->name, "stats_map_", 10) == 0 ||
	    strncmp(map->name, "map_netd_stats", 14) == 0) {
		struct vh_stats_key *sk = (struct vh_stats_key *)key;
		struct net_device *dev;
		char ifname[IFNAMSIZ];

		ifname[0] = '\0';
		rcu_read_lock();
		dev = dev_get_by_index_rcu(&init_net, sk->ifaceIndex);
		if (dev) {
			strncpy(ifname, dev->name, IFNAMSIZ - 1);
			ifname[IFNAMSIZ - 1] = '\0';
		}
		rcu_read_unlock();

		vpnhide_dbg(
			"key_check stats_map '%s': uid=%u index=%u ifname='%s'\n",
			map->name, sk->uid, sk->ifaceIndex, ifname);

		if (is_vpn_ifname(ifname) || is_target_uid_val(sk->uid)) {
			vpnhide_dbg(
				"BPF Match stats_map '%s': uid=%u index=%u ifname='%s' -> SPOOFING ZERO STATS\n",
				map->name, sk->uid, sk->ifaceIndex, ifname);
			return true;
		}
	} else if (strncmp(map->name, "iface_stats", 11) == 0 ||
		   strncmp(map->name, "map_netd_iface_", 15) == 0 ||
		   strncmp(map->name, "tether_stats", 12) == 0) {
		u32 ifaceIndex = *(u32 *)key;
		struct net_device *dev;
		char ifname[IFNAMSIZ];

		ifname[0] = '\0';
		rcu_read_lock();
		dev = dev_get_by_index_rcu(&init_net, ifaceIndex);
		if (dev) {
			strncpy(ifname, dev->name, IFNAMSIZ - 1);
			ifname[IFNAMSIZ - 1] = '\0';
		}
		rcu_read_unlock();

		vpnhide_dbg(
			"key_check iface/tether '%s': index=%u ifname='%s'\n",
			map->name, ifaceIndex, ifname);

		if (is_vpn_ifname(ifname)) {
			vpnhide_dbg(
				"BPF Match iface/tether stats '%s': index=%u ifname='%s' -> SPOOFING ZERO STATS\n",
				map->name, ifaceIndex, ifname);
			return true;
		}
	}
	/* app_uid_stats / uid_stats (key = uid only, no iface) are intentionally
	 * NOT filtered here: the per-uid total must keep growing so that
	 * delta-based traffic checks in apps do not see zero. */

	return false;
}

/* ================================================================== */
struct sys_bpf_data {
	int cmd;
	union bpf_attr __user *uattr;
	unsigned int size;
	union bpf_attr attr;
	u32 map_fd;
};

static struct kretprobe sys_bpf_krp;

static int sys_bpf_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct sys_bpf_data *data = (struct sys_bpf_data *)ri->data;
	int cmd;
	union bpf_attr __user *uattr;
	unsigned int size;

	if (!is_hook_active(17) || is_target_uid()) {
		data->uattr = NULL;
		return 0;
	}

	if (sys_bpf_krp.kp.symbol_name &&
	    strcmp(sys_bpf_krp.kp.symbol_name, "__arm64_sys_bpf") == 0) {
		struct pt_regs *user_regs = (struct pt_regs *)regs->regs[0];
		if (user_regs &&
		    (unsigned long)user_regs >= 0xFFFF000000000000ULL) {
			cmd = (int)user_regs->regs[0];
			uattr = (union bpf_attr __user *)user_regs->regs[1];
			size = (unsigned int)user_regs->regs[2];
		} else {
			data->uattr = NULL;
			return 0;
		}
	} else {
		cmd = (int)regs->regs[0];
		uattr = (union bpf_attr __user *)regs->regs[1];
		size = (unsigned int)regs->regs[2];
	}

	data->cmd = cmd;
	data->uattr = uattr;
	data->size = size;
	data->map_fd = 0;

	if (uattr &&
	    (cmd == BPF_MAP_LOOKUP_ELEM || cmd == BPF_MAP_UPDATE_ELEM ||
	     cmd == BPF_MAP_DELETE_ELEM || cmd == BPF_MAP_GET_NEXT_KEY ||
	     cmd == BPF_MAP_LOOKUP_AND_DELETE_ELEM ||
	     cmd == BPF_MAP_LOOKUP_BATCH ||
	     cmd == BPF_MAP_LOOKUP_AND_DELETE_BATCH)) {
		unsigned int copy_sz =
			min_t(unsigned int, size, sizeof(data->attr));
		memset(&data->attr, 0, sizeof(data->attr));
		if (copy_from_user(&data->attr, uattr, copy_sz)) {
			data->uattr = NULL;
		} else {
			if (cmd == BPF_MAP_LOOKUP_BATCH ||
			    cmd == BPF_MAP_LOOKUP_AND_DELETE_BATCH) {
				data->map_fd = data->attr.batch.map_fd;
			} else {
				data->map_fd = data->attr.map_fd;
			}
		}
	}
	return 0;
}

static int sys_bpf_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct sys_bpf_data *data = (struct sys_bpf_data *)ri->data;
	struct fd f;
	struct file *file_ptr;
	int ret_val = regs_return_value(regs);

	if (!data || !data->uattr)
		return 0;

	/* BATCH lookups can return -ENOENT but still populate some keys/values */
	if (ret_val < 0 && ret_val != -ENOENT)
		return 0;

	if (data->cmd == BPF_MAP_LOOKUP_ELEM ||
	    data->cmd == BPF_MAP_LOOKUP_AND_DELETE_ELEM ||
	    data->cmd == BPF_MAP_LOOKUP_BATCH ||
	    data->cmd == BPF_MAP_LOOKUP_AND_DELETE_BATCH) {
		f = fdget(data->map_fd);
		file_ptr = vh_fd_file(f);
		if (file_ptr) {
			unsigned long magic = 0;
			const char *dname = "unknown";
			if (file_ptr->f_path.dentry) {
				dname = file_ptr->f_path.dentry->d_name.name;
				if (file_ptr->f_path.dentry->d_sb) {
					magic = file_ptr->f_path.dentry->d_sb
							->s_magic;
				}
			}
			if (file_ptr->private_data) {
				bool is_bpf_file = false;
				if (magic == BPF_FS_MAGIC) {
					is_bpf_file = true;
				} else if ((magic == 0x09041934 ||
					    magic == 0x09041957) &&
					   dname &&
					   strcmp(dname, "bpf-map") == 0) {
					is_bpf_file = true;
				}

				if (is_bpf_file) {
					struct bpf_map *map =
						file_ptr->private_data;
					if (map && !IS_ERR(map)) {
						if (is_stats_or_uid_map(
							    map->name)) {
							u32 key_size =
								map->key_size;
							u32 value_size =
								map->value_size;

							vpnhide_dbg(
								"sys_bpf_ret: matched map '%s', cmd=%d\n",
								map->name,
								data->cmd);

							/* Handle single lookup */
							if ((data->cmd ==
								     BPF_MAP_LOOKUP_ELEM ||
							     data->cmd ==
								     BPF_MAP_LOOKUP_AND_DELETE_ELEM) &&
							    ret_val == 0) {
								void *kbuf = kmalloc(
									key_size,
									GFP_KERNEL);
								void *vbuf = kzalloc(
									value_size,
									GFP_KERNEL);
								if (kbuf &&
								    vbuf) {
									void __user *usr_key =
										(void __user
											 *)(unsigned long)data
											->attr
											.key;
									void __user *usr_val =
										(void __user
											 *)(unsigned long)data
											->attr
											.value;
									if (copy_from_user(
										    kbuf,
										    usr_key,
										    key_size) ==
									    0) {
										if (is_key_vpn_or_target_uid(
											    map,
											    kbuf)) {
											vpnhide_dbg(
												"sys_bpf_ret: single zeroing for map '%s'\n",
												map->name);
											if (copy_to_user(
												    usr_val,
												    vbuf,
												    value_size)) {
												vpnhide_dbg(
													"sys_bpf_ret: single zeroing copy_to_user failed\n");
											}
										}
									}
								}
								kfree(kbuf);
								kfree(vbuf);
							}
							/* Handle batch lookup */
							else if (data->cmd ==
									 BPF_MAP_LOOKUP_BATCH ||
								 data->cmd ==
									 BPF_MAP_LOOKUP_AND_DELETE_BATCH) {
								u32 count = 0;
								if (get_user(
									    count,
									    &data->uattr
										     ->batch
										     .count) ==
									    0 &&
								    count > 0) {
									void __user *usr_keys =
										(void __user
											 *)(unsigned long)data
											->attr
											.batch
											.keys;
									void __user *usr_vals =
										(void __user
											 *)(unsigned long)data
											->attr
											.batch
											.values;
									if (usr_keys &&
									    usr_vals) {
										u32 i;
										void *kbuf = kmalloc(
											key_size,
											GFP_KERNEL);
										void *vbuf = kmalloc(
											value_size,
											GFP_KERNEL);
										if (kbuf &&
										    vbuf) {
											if (strncmp(map->name,
												    "iface_stats",
												    11) ==
												    0 ||
											    strncmp(map->name,
												    "map_netd_iface_stats",
												    20) ==
												    0) {
												struct vh_stats_value
													vpn_sum = {
														0
													};
												u32 cover_idx =
													(u32)atomic_read(
														&global_cover_ifindex);
												u32 cover_pos =
													UINT_MAX;

												for (i = 0;
												     i <
												     count;
												     i++) {
													u32 ifindex;
													char vpn_ifname
														[IFNAMSIZ];
													struct net_device
														*dev;

													if (copy_from_user(
														    kbuf,
														    (char __user
															     *)usr_keys +
															    i * key_size,
														    key_size))
														continue;
													ifindex = *(
														u32 *)kbuf;

													vpn_ifname[0] =
														'\0';
													rcu_read_lock();
													dev = dev_get_by_index_rcu(
														&init_net,
														ifindex);
													if (dev) {
														strncpy(vpn_ifname,
															dev->name,
															IFNAMSIZ -
																1);
														vpn_ifname[IFNAMSIZ -
															   1] =
															'\0';
													}
													rcu_read_unlock();

													if (is_vpn_ifname(
														    vpn_ifname)) {
														if (copy_from_user(
															    vbuf,
															    (char __user
																     *)usr_vals +
																    i * value_size,
															    value_size) ==
														    0) {
															struct vh_stats_value
																*sv = (struct vh_stats_value
																	       *)
																	vbuf;
															sv_add(&vpn_sum,
															       sv);
														}
														memset(vbuf,
														       0,
														       value_size);
														if (copy_to_user(
															    (char __user
																     *)usr_vals +
																    i * value_size,
															    vbuf,
															    value_size)) {
															vpnhide_dbg(
																"sys_bpf_ret: batch zeroing copy_to_user failed\n");
														}
													} else if (
														cover_idx &&
														ifindex ==
															cover_idx) {
														cover_pos =
															i;
													}
												}
												if (cover_pos !=
													    UINT_MAX &&
												    (sv_rx_bytes(
													     &vpn_sum) ||
												     sv_tx_bytes(
													     &vpn_sum))) {
													if (copy_from_user(
														    vbuf,
														    (char __user
															     *)usr_vals +
															    cover_pos *
																    value_size,
														    value_size) ==
													    0) {
														struct vh_stats_value
															*sv = (struct vh_stats_value
																       *)
																vbuf;
														sv_add(sv,
														       &vpn_sum);
														if (copy_to_user(
															    (char __user
																     *)usr_vals +
																    cover_pos *
																	    value_size,
															    vbuf,
															    value_size)) {
															vpnhide_dbg(
																"sys_bpf_ret: batch cover update copy_to_user failed\n");
														}
													}
												}
											} else {
												for (i = 0;
												     i <
												     count;
												     i++) {
													if (copy_from_user(
														    kbuf,
														    (char __user
															     *)usr_keys +
															    i * key_size,
														    key_size) ==
													    0) {
														if (is_key_vpn_or_target_uid(
															    map,
															    kbuf)) {
															memset(vbuf,
															       0,
															       value_size);
															if (copy_to_user(
																    (char __user
																	     *)usr_vals +
																	    i * value_size,
																    vbuf,
																    value_size)) {
																vpnhide_dbg(
																	"sys_bpf_ret: batch zeroing copy_to_user failed\n");
															}
														}
													}
												}
											}
										}
										kfree(kbuf);
										kfree(vbuf);
									}
								}
							}
						}
					}
				}
			}
			fdput(f);
		}
	}
	return 0;
}

static struct kretprobe sys_bpf_krp = {
	.entry_handler = sys_bpf_entry,
	.handler = sys_bpf_ret,
	.data_size = sizeof(struct sys_bpf_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_bpf",
};

/* ================================================================== */
/*  Module init / exit                                                */
/* ================================================================== */

/* Stealthy IOCTL interface replaces /proc files */

struct kretprobe_reg {
	struct kretprobe *krp;
	const char *name;
	const char *fallback;
	bool registered;
};

static struct kretprobe sock_ioctl_krp = {
	.handler = sock_ioctl_ret,
	.entry_handler = sock_ioctl_entry,
	.data_size = sizeof(struct sock_ioctl_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "sock_ioctl",
};

static struct kretprobe_reg probes[] = {
	{ &dev_ioctl_krp, "dev_ioctl", NULL, false },
	{ &sock_ioctl_krp, "sock_ioctl", NULL, false },
	{ &rtnl_fill_krp, "rtnl_fill_ifinfo", NULL, false },
	{ &inet6_fill_krp, "inet6_fill_ifaddr", NULL, false },
	{ &inet_fill_krp, "inet_fill_ifaddr", NULL, false },
	{ &fib_route_krp, "fib_route_seq_show", NULL, false },
	{ &ipv6_route_krp, "ipv6_route_seq_show", NULL, false },
	{ &fib_dump_krp, "fib_dump_info", NULL, false },
	{ &fib_rule_fill_krp, "fib_nl_fill_rule", NULL, false },
	{ &rt6_fill_krp, "rt6_fill_node", NULL, false },
	{ &rt_fill_krp, "rt_fill_info", NULL, false },
	{ &sock_setsockopt_krp, "sock_setsockopt", NULL, false },
	{ &sock_common_setsockopt_krp, "sock_common_setsockopt", NULL, false },
	{ &sock_getsockopt_krp, "sock_getsockopt", NULL, false },
	{ &sock_common_getsockopt_krp, "sock_common_getsockopt", NULL, false },
	{ &socket_connect_krp, "security_socket_connect", NULL, false },
	{ &socket_bind_krp, "security_socket_bind", NULL, false },
	{ &inet_getname_krp, "inet_getname", NULL, false },
	{ &inet6_getname_krp, "inet6_getname", NULL, false },
	{ &sys_bpf_krp, "__arm64_sys_bpf", NULL, false },
};

static int __init vpnhide_init(void)
{
	int i, ret, ok = 0;

	/* Initialize RCU targets pointers */
	rcu_assign_pointer(global_targets, NULL);
	rcu_assign_pointer(global_port_targets, NULL);

	for (i = 0; i < ARRAY_SIZE(probes); i++) {
		ret = register_kretprobe(probes[i].krp);
		if (ret < 0) {
			pr_warn(MODNAME ": kretprobe(%s) failed: %d\n",
				probes[i].name, ret);
		} else {
			probes[i].registered = true;
			ok++;
			vpnhide_dbg("kretprobe(%s) registered\n",
				    probes[i].name);
		}
	}

	ret = misc_register(&vpnhide_misc);
	if (ret) {
		pr_err(MODNAME ": failed to register misc device\n");
	}

	vpnhide_dbg("loaded\n");
	return 0;
}

static void __exit vpnhide_exit(void)
{
	struct vpnhide_targets *t;
	struct vpnhide_port_targets *t_port;
	int i;

	for (i = 0; i < ARRAY_SIZE(probes); i++) {
		if (probes[i].registered) {
			unregister_kretprobe(probes[i].krp);
			vpnhide_dbg("kretprobe(%s) unregistered (missed %d)\n",
				    probes[i].name, probes[i].krp->nmissed);
		}
	}

	/* Cleanup RCU targets */
	spin_lock(&targets_update_lock);
	t = rcu_dereference_protected(global_targets,
				      lockdep_is_held(&targets_update_lock));
	rcu_assign_pointer(global_targets, NULL);
	spin_unlock(&targets_update_lock);

	if (t) {
		synchronize_rcu();
		kfree(t);
	}

	/* Cleanup RCU port targets */
	spin_lock(&port_targets_update_lock);
	t_port = rcu_dereference_protected(
		global_port_targets,
		lockdep_is_held(&port_targets_update_lock));
	rcu_assign_pointer(global_port_targets, NULL);
	spin_unlock(&port_targets_update_lock);

	if (t_port) {
		synchronize_rcu();
		kfree(t_port);
	}

	misc_deregister(&vpnhide_misc);

	vpnhide_dbg("unloaded\n");
}

module_init(vpnhide_init);
module_exit(vpnhide_exit);

/* The source is MIT-licensed (see SPDX header), but MODULE_LICENSE("GPL")
 * is required to resolve EXPORT_SYMBOL_GPL symbols (kretprobes, etc.)
 * at module load time. */
MODULE_LICENSE("GPL");
MODULE_AUTHOR("soranerai");
MODULE_DESCRIPTION("Hide VPN interfaces from selected apps at kernel level");
