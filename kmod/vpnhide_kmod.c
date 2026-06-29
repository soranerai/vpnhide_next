// SPDX-License-Identifier: MIT
/*
 * vpnhide_kmod — kernel module that hides VPN network interfaces from
 * selected Android apps by filtering ioctl, netlink, and procfs
 * responses based on the calling process's UID.
 *
 * Uses kretprobes so no modification of the running kernel is needed;
 * works on stock Android GKI kernels with CONFIG_KPROBES=y.
 *
 * Hook Catalog & Consequence of Absence (What happens without them):
 *   - dev_ioctl: Filters per-interface ioctls (SIOCGIFFLAGS, SIOCGIFINDEX, etc.).
 *     Without it: Target apps can query state and ifindex of VPN interfaces directly by name.
 *   - sock_ioctl: Filters SIOCGIFCONF interface lists.
 *     Without it: Target apps can list all active network interfaces and discover the VPN interface.
 *   - rtnl_fill_ifinfo: Filters RTM_NEWLINK netlink dumps.
 *     Without it: Netlink link dumps (getifaddrs() path) will reveal the VPN interface details.
 *   - inet_fill_ifaddr / inet6_fill_ifaddr: Filters RTM_GETADDR netlink addresses.
 *     Without them: VPN IP addresses leak; getifaddrs() reconstructs dummy interfaces using them.
 *   - fib_route_seq_show / ipv6_route_seq_show: Filters /proc/net/route & ipv6_route.
 *     Without them: Routing rules mapped to the VPN interface will leak in procfs.
 *   - fib_dump_info / rt6_fill_node / rt_fill_info: Filters routing table dumps (RTM_GETROUTE).
 *     Without them: IPv4/IPv6 routing queries will return entries referencing the VPN gateway/interface.
 *   - fib_nl_fill_rule: Filters policy routing rules (RTM_GETRULE).
 *     Without it: Target UID policy rules mapping traffic to VPN tables will leak.
 *   - setsockopt / getsockopt / sk_getsockopt: Handles bind/mark and MTU/MSS settings.
 *     Without them: Apps can bind to VPN interfaces or detect the tunnel via MTU size anomalies.
 *   - connect / bind: Controls port/host access on loopback/local interfaces.
 *     Without them: Apps can connect to local proxies or detect active proxy ports via EADDRINUSE.
 *   - inet6_bind (link-local): Intercepts AF_INET6 bind probes with fe80::/10 + VPN scope_id.
 *     Without it: Iterating scope_id 1..N and binding fe80::1 reveals active VPN indices
 *     without any enumeration syscall (check_ipv6_link_local_bruteforce Pass 1).
 *   - getname / getsockname: Spoofs local socket addresses.
 *     Without it: getsockname() queries will return the VPN interface's private IP address.
 *   - sys_bpf: Hijacks eBPF stats maps queries.
 *     Without it: Modern Android NetworkStatsManager leaks VPN interface indexes and traffic counters.
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
#include <linux/delay.h>
#include <linux/tcp.h>

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

#ifndef SO_TIMESTAMPING
#define SO_TIMESTAMPING 37
#endif

#ifndef SOF_TIMESTAMPING_TX_HARDWARE
#define SOF_TIMESTAMPING_TX_HARDWARE (1 << 0)
#define SOF_TIMESTAMPING_RX_HARDWARE (1 << 2)
#define SOF_TIMESTAMPING_RAW_HARDWARE (1 << 6)
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

enum vpnhide_hook_idx {
	HOOK_DEV_IOCTL = 0,
	HOOK_SOCK_IOCTL = 1,
	HOOK_RTNL_FILL = 2,
	HOOK_INET6_FILL = 3,
	HOOK_INET_FILL = 4,
	HOOK_FIB_ROUTE = 5,
	HOOK_IPV6_ROUTE = 6,
	HOOK_FIB_DUMP = 7,
	HOOK_FIB_RULE_FILL = 8,
	HOOK_RT6_FILL = 9,
	HOOK_RT_FILL = 10,
	HOOK_SETSOCKOPT = 11,
	HOOK_GETSOCKOPT = 12,
	HOOK_CONNECT = 13,
	HOOK_GETNAME_INET = 14,
	HOOK_GETNAME_INET6 = 15,
	HOOK_BIND = 16,
	HOOK_BPF = 17,
	HOOK_GETDENTS64 = 18,
	HOOK_FACCESSAT = 19,
	HOOK_FACCESSAT2 = 20,
	HOOK_NEWFSTATAT = 21,
	HOOK_OPENAT = 22,
	HOOK_OPENAT2 = 23,
	HOOK_READLINKAT = 24,
	HOOK_UDP_SENDMSG = 25,
	HOOK_DEV_SEQ = 26,
	HOOK_IF6_SEQ = 27,
	HOOK_INET6_BIND_LL = 28,
	HOOK_UDPV6_SENDMSG = 29,
	HOOK_FIB_TRIE = 30,
	HOOK_TC_FILL_QDISC = 31,
};

static inline bool is_hook_active(enum vpnhide_hook_idx index)
{
	return (READ_ONCE(active_hooks_mask) & (1u << index)) != 0;
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

static struct vpnhide_targets __rcu *global_lsposed_targets;
static DEFINE_SPINLOCK(lsposed_targets_update_lock);

static DECLARE_WAIT_QUEUE_HEAD(vpnhide_config_wait);
static atomic_t vpnhide_config_generation = ATOMIC_INIT(1);
static atomic_t java_stats_clear_generation = ATOMIC_INIT(1);
static unsigned int java_hooks_mask = 0xFFFFFFFF;

static struct vpnhide_port_targets __rcu *global_port_targets;
static DEFINE_SPINLOCK(port_targets_update_lock);

struct vpnhide_iface_prefixes {
	int count;
	char prefixes[MAX_IFACE_PREFIXES][MAX_IFACE_LEN];
	struct rcu_head rcu;
};

static struct vpnhide_iface_prefixes __rcu *global_iface_prefixes;
static DEFINE_SPINLOCK(iface_prefixes_lock);

struct vpnhide_spoof_ip_rcu {
	struct vpnhide_spoof_ip sip;
	struct rcu_head rcu;
};

static struct vpnhide_spoof_ip_rcu __rcu *global_spoof_ip;
static DEFINE_SPINLOCK(spoof_ip_lock);

static void free_spoof_ip_rcu(struct rcu_head *head)
{
	struct vpnhide_spoof_ip_rcu *p =
		container_of(head, struct vpnhide_spoof_ip_rcu, rcu);
	kfree(p);
}

static int update_spoof_ip(const struct vpnhide_spoof_ip *sip)
{
	struct vpnhide_spoof_ip_rcu *new_rcu, *old_rcu;

	new_rcu = kmalloc(sizeof(*new_rcu), GFP_ATOMIC);
	if (!new_rcu)
		return -ENOMEM;

	new_rcu->sip = *sip;

	spin_lock(&spoof_ip_lock);
	old_rcu = rcu_dereference_protected(global_spoof_ip,
					    lockdep_is_held(&spoof_ip_lock));
	rcu_assign_pointer(global_spoof_ip, new_rcu);
	spin_unlock(&spoof_ip_lock);

	if (old_rcu)
		call_rcu(&old_rcu->rcu, free_spoof_ip_rcu);

	return 0;
}

static void get_spoof_ip(struct vpnhide_spoof_ip *dst)
{
	struct vpnhide_spoof_ip_rcu *p;

	rcu_read_lock();
	p = rcu_dereference(global_spoof_ip);
	if (p) {
		*dst = p->sip;
	} else {
		memset(dst, 0, sizeof(*dst));
	}
	rcu_read_unlock();
}

/* Ifindex of the cover (non-VPN) interface, sent by the daemon.
 * Used in vh_stats_map_lookup to avoid scanning all interfaces.
 * 0 means not set yet. */
static atomic_t global_cover_ifindex = ATOMIC_INIT(0);

struct vpnhide_active_vpns {
	int count;
	struct vpnhide_active_vpn vpns[MAX_ACTIVE_VPNS];
	struct rcu_head rcu;
};
static struct vpnhide_active_vpns __rcu *global_active_vpns;
static DEFINE_SPINLOCK(active_vpns_lock);

static inline u32 fnv1a_name(const char *s, int maxlen)
{
	u32 hash = 2166136261u;
	int i;
	for (i = 0; i < maxlen && s[i] != '\0'; i++) {
		hash ^= (u8)s[i];
		hash *= 16777619u;
	}
	return hash;
}

struct vh_vpn_name_cache {
	int count;
	u32 hashes[MAX_ACTIVE_VPNS];
	char names[MAX_ACTIVE_VPNS][MAX_IFACE_LEN];
	struct rcu_head rcu;
};

static struct vh_vpn_name_cache __rcu *g_vpn_name_cache;
static DEFINE_SPINLOCK(g_vpn_name_cache_lock);

static void free_vpn_name_cache_rcu(struct rcu_head *head)
{
	struct vh_vpn_name_cache *p =
		container_of(head, struct vh_vpn_name_cache, rcu);
	kfree(p);
}

static void vh_rebuild_name_cache(const struct vpnhide_vpn_ifindexes *idata)
{
	struct vh_vpn_name_cache *new_c, *old_c;
	int i;

	new_c = kzalloc(sizeof(*new_c), GFP_KERNEL);
	if (!new_c)
		return;

	new_c->count = (idata->count < MAX_ACTIVE_VPNS) ? idata->count :
							  MAX_ACTIVE_VPNS;
	for (i = 0; i < new_c->count; i++) {
		strncpy(new_c->names[i], idata->vpns[i].name,
			MAX_IFACE_LEN - 1);
		new_c->names[i][MAX_IFACE_LEN - 1] = '\0';
		new_c->hashes[i] = fnv1a_name(new_c->names[i], MAX_IFACE_LEN);
	}

	spin_lock(&g_vpn_name_cache_lock);
	old_c = rcu_dereference_protected(
		g_vpn_name_cache, lockdep_is_held(&g_vpn_name_cache_lock));
	rcu_assign_pointer(g_vpn_name_cache, new_c);
	spin_unlock(&g_vpn_name_cache_lock);

	if (old_c)
		call_rcu(&old_c->rcu, free_vpn_name_cache_rcu);
}

static bool __always_inline vh_is_vpn_name_cached(const char *name, size_t len)
{
	struct vh_vpn_name_cache *c;
	u32 h;
	int i;
	bool found = false;

	if (unlikely(!name || len == 0))
		return false;

	h = fnv1a_name(name, len);

	rcu_read_lock();
	c = rcu_dereference(g_vpn_name_cache);
	if (likely(c)) {
		for (i = 0; i < c->count; i++) {
			if (c->hashes[i] == h &&
			    strncmp(c->names[i], name, MAX_IFACE_LEN) == 0) {
				found = true;
				break;
			}
		}
	}
	rcu_read_unlock();
	return found;
}

static bool is_active_vpn_ifindex(u32 ifindex)
{
	struct vpnhide_active_vpns *vpns;
	bool found = false;
	int i;

	rcu_read_lock();
	vpns = rcu_dereference(global_active_vpns);
	if (vpns) {
		for (i = 0; i < vpns->count; i++) {
			if (vpns->vpns[i].ifindex == ifindex) {
				found = true;
				break;
			}
		}
	}
	rcu_read_unlock();
	return found;
}

static bool is_active_vpn_ifname(const char *name)
{
	struct vpnhide_active_vpns *vpns;
	bool found = false;
	int i;

	if (!name || name[0] == '\0')
		return false;

	rcu_read_lock();
	vpns = rcu_dereference(global_active_vpns);
	if (vpns) {
		for (i = 0; i < vpns->count; i++) {
			if (strncmp(vpns->vpns[i].name, name, MAX_IFACE_LEN) ==
			    0) {
				found = true;
				break;
			}
		}
	}
	rcu_read_unlock();
	return found;
}

#undef is_vpn_ifname
#define is_vpn_ifname(name) is_active_vpn_ifname(name)

static bool is_target_uid_val(uid_t uid)
{
	struct vpnhide_targets *t;
	bool found = false;
	int i;

	if (uid == 0 || uid == 1000)
		return false;

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
	u32 ioctl_counts[BUCKETS_COUNT]; /* type 0 */
	u32 netlink_counts[BUCKETS_COUNT]; /* type 1 */
	u32 proc_counts[BUCKETS_COUNT]; /* type 2 */
	u32 sockopt_counts[BUCKETS_COUNT]; /* type 3 */
	u32 connect_counts[BUCKETS_COUNT]; /* type 4 */
	u32 getname_counts[BUCKETS_COUNT]; /* type 5 */
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
				kmod_stats[i].proc_counts[idx] = 0;
				kmod_stats[i].sockopt_counts[idx] = 0;
				kmod_stats[i].connect_counts[idx] = 0;
				kmod_stats[i].getname_counts[idx] = 0;
				kmod_stats[i].bucket_times[idx] = now_min;
			}
			if (type == 0)
				kmod_stats[i].ioctl_counts[idx]++;
			else if (type == 1)
				kmod_stats[i].netlink_counts[idx]++;
			else if (type == 2)
				kmod_stats[i].proc_counts[idx]++;
			else if (type == 3)
				kmod_stats[i].sockopt_counts[idx]++;
			else if (type == 4)
				kmod_stats[i].connect_counts[idx]++;
			else if (type == 5)
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
		memset(kmod_stats[kmod_stats_count].proc_counts, 0,
		       sizeof(kmod_stats[kmod_stats_count].proc_counts));
		memset(kmod_stats[kmod_stats_count].sockopt_counts, 0,
		       sizeof(kmod_stats[kmod_stats_count].sockopt_counts));
		memset(kmod_stats[kmod_stats_count].connect_counts, 0,
		       sizeof(kmod_stats[kmod_stats_count].connect_counts));
		memset(kmod_stats[kmod_stats_count].getname_counts, 0,
		       sizeof(kmod_stats[kmod_stats_count].getname_counts));
		memset(kmod_stats[kmod_stats_count].bucket_times, 0,
		       sizeof(kmod_stats[kmod_stats_count].bucket_times));

		kmod_stats[kmod_stats_count].bucket_times[idx] = now_min;
		if (type == 0)
			kmod_stats[kmod_stats_count].ioctl_counts[idx] = 1;
		else if (type == 1)
			kmod_stats[kmod_stats_count].netlink_counts[idx] = 1;
		else if (type == 2)
			kmod_stats[kmod_stats_count].proc_counts[idx] = 1;
		else if (type == 3)
			kmod_stats[kmod_stats_count].sockopt_counts[idx] = 1;
		else if (type == 4)
			kmod_stats[kmod_stats_count].connect_counts[idx] = 1;
		else if (type == 5)
			kmod_stats[kmod_stats_count].getname_counts[idx] = 1;

		kmod_stats_count++;
	}
	spin_unlock_irqrestore(&kmod_stats_lock, flags);
}

/* ================================================================== */
/*  Hook 1: dev_ioctl — all per-interface ioctls                      */
/*  Android source path: net/core/dev_ioctl.c                         */
/*                                                                    */
/*  What it does:                                                     */
/*    Filters per-interface ioctls (SIOCGIFFLAGS, SIOCGIFNAME,        */
/*    SIOCGIFMTU, SIOCGIFINDEX, SIOCGIFHWADDR, SIOCGIFADDR) and       */
/*    returns -ENODEV if a target app queries a VPN interface.        */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps can query state, MTU, ifindex, MAC, or IP address of*/
/*    the VPN interface by its name directly, exposing its existence. */
/*                                                                    */
/*  dev_ioctl() on GKI 6.1:                                           */
/*    int dev_ioctl(struct net *net, unsigned int cmd,                */
/*                  struct ifreq *ifr, void __user *data,             */
/*                  bool *need_copyout)                               */
/*  arm64: x0=net, x1=cmd, x2=ifr (KERNEL ptr), x3=data (__user)      */
/*                                                                    */
/*  Note: SIOCGIFCONF goes through sock_ioctl -> dev_ifconf, not      */
/*  through dev_ioctl, so it is not covered here.                     */
/* ================================================================== */

struct dev_ioctl_data {
	unsigned int cmd;
	struct ifreq *kifr; /* kernel pointer, saved from x2 */
};

static int dev_ioctl_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct dev_ioctl_data *data;
	if (!is_hook_active(HOOK_DEV_IOCTL))
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->cmd = (unsigned int)regs->regs[1];
	data->kifr = (struct ifreq *)regs->regs[2];

	vpnhide_dbg("dev_ioctl_entry: uid=%u cmd=0x%x\n",
		    from_kuid(&init_user_ns, current_uid()), data->cmd);
	return 0;
}

static int dev_ioctl_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct dev_ioctl_data *data = (void *)ri->data;
	char name[IFNAMSIZ];

	if (regs_return_value(regs) != 0)
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
				      0);
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
/*  What it does:                                                     */
/*    Intercepts unlocked_ioctl on sockets, checks for SIOCGIFCONF,   */
/*    and filters out VPN interface structures from the list of all   */
/*    active network interfaces returned to target UIDs.              */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps can enumerate all active network interfaces on the  */
/*    system via ioctl(..., SIOCGIFCONF), immediately leaking the VPN.*/
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
				      0);
		vpnhide_dbg("ifconf filtered %d -> %d bytes\n", orig_len,
			    ifc.ifc_len);
	}

	return 0;
}

/* ================================================================== */
/*  Hook 2b: sock_setsockopt — Aikido Bind Sabotage                    */
/*  Android source path: net/socket.c                                 */
/*                                                                    */
/*  What it does:                                                     */
/*    Intercepts setsockopt calls. If a target app tries to bind a    */
/*    socket to a VPN interface (via SO_BINDTODEVICE or               */
/*    SO_BINDTOIFINDEX), it overrides the operation and returns       */
/*    -ENODEV. It also handles custom control command options and     */
/*    adjusts MTU discovery options.                                  */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps can bind their sockets directly to the VPN          */
/*    interface, bypass normal policy routing, or detect if the VPN   */
/*    is present by checking if binding succeeds.                     */
/*                                                                    */
/*  sock_setsockopt(struct socket *sock, int level, int optname,      */
/*                  sockptr_t optval, unsigned int optlen)            */
/* ================================================================== */

struct sock_setsockopt_data {
	bool override_ret;
	int deny_errno;
	bool intercepted;
};

static bool sys_setsockopt_uses_wrapper;
static struct kretprobe sys_setsockopt_krp;

static int sys_setsockopt_entry(struct kretprobe_instance *ri,
				struct pt_regs *regs)
{
	struct sock_setsockopt_data *sdata;
	int fd, level, optname, optlen;
	void __user *optval_ptr;
	char name[IFNAMSIZ];

	if (!is_hook_active(HOOK_SETSOCKOPT))
		return 1;

	sdata = (void *)ri->data;
	sdata->override_ret = false;
	sdata->deny_errno = 0;
	sdata->intercepted = false;

	if (sys_setsockopt_uses_wrapper) {
		struct pt_regs *user_regs = (struct pt_regs *)regs->regs[0];
		if (user_regs &&
		    (unsigned long)user_regs >= 0xFFFF000000000000ULL) {
			fd = (int)user_regs->regs[0];
			level = (int)user_regs->regs[1];
			optname = (int)user_regs->regs[2];
			optval_ptr = (void __user *)user_regs->regs[3];
			optlen = (int)user_regs->regs[4];
		} else {
			return 1;
		}
	} else {
		fd = (int)regs->regs[0];
		level = (int)regs->regs[1];
		optname = (int)regs->regs[2];
		optval_ptr = (void __user *)regs->regs[3];
		optlen = (int)regs->regs[4];
	}

	if (level == 0x5648 && optname == 0x88) {
		uid_t uid = from_kuid(&init_user_ns, current_uid());
		if (uid == 1000 || uid == 0) {
			struct vpnhide_spoof_ip sip;
			if (optlen == sizeof(sip)) {
				if (copy_from_user(&sip, optval_ptr,
						   sizeof(sip)) == 0) {
					if (update_spoof_ip(&sip) == 0) {
						vpnhide_dbg(
							"sys_setsockopt: updated spoof IP: IPv4=%pI4 (%d), IPv6=%pI6c (%d)\n",
							&sip.ipv4_addr,
							sip.has_ipv4,
							sip.ipv6_addr,
							sip.has_ipv6);
						sdata->override_ret = true;
					}
				}
			}
		}
		if (!sdata->override_ret)
			return 1;
		return 0;
	}

	if (!is_target_uid())
		return 1;

	if (level == SOL_SOCKET) {
		if (optname == SO_BINDTODEVICE) {
			if (optlen <= 0)
				return 0;
			if (optlen >= IFNAMSIZ)
				optlen = IFNAMSIZ - 1;

			if (copy_from_user(name, optval_ptr, optlen))
				return 0;
			name[optlen] = '\0';

			if (is_vpn_ifname(name)) {
				vpnhide_dbg(
					"sys_setsockopt: denying SO_BINDTODEVICE to VPN iface '%s' with ENODEV\n",
					name);
				sdata->override_ret = true;
				sdata->deny_errno = ENODEV;
				sdata->intercepted = true;
			}
		} else if (optname == SO_BINDTOIFINDEX) {
			int ifindex;

			if (optlen != sizeof(int))
				return 0;
			if (get_user(ifindex, (int __user *)optval_ptr))
				return 0;

			if (ifindex <= 0)
				return 0;

			if (is_active_vpn_ifindex(ifindex)) {
				vpnhide_dbg(
					"sys_setsockopt: denying SO_BINDTOIFINDEX %d with ENODEV\n",
					ifindex);
				sdata->override_ret = true;
				sdata->deny_errno = ENODEV;
				sdata->intercepted = true;
			}
		} else if (optname == SO_MARK) {
			int mark;
			if (optlen != sizeof(int))
				return 0;
			if (get_user(mark, (int __user *)optval_ptr))
				return 0;

			if (mark != 0) {
				vpnhide_dbg(
					"sys_setsockopt: target app tried to set SO_MARK to 0x%x, overriding to 0\n",
					mark);
				if (put_user(0, (int __user *)optval_ptr) ==
				    0) {
					sdata->intercepted = true;
				}
			}
		} else if (optname == SO_TIMESTAMPING) {
			/* Strip hardware timestamp bits so virtual interfaces
			 * cannot be fingerprinted via missing hw timestamps.
			 * ts[2] (raw hardware ts) is always zero on TUN/VPN;
			 * without stripping, userspace detects software-only
			 * path ↔ virtual interface. */
			int flags;
			if (optlen == sizeof(int) &&
			    get_user(flags, (int __user *)optval_ptr) == 0) {
				int stripped = flags &
					       ~(SOF_TIMESTAMPING_TX_HARDWARE |
						 SOF_TIMESTAMPING_RX_HARDWARE |
						 SOF_TIMESTAMPING_RAW_HARDWARE);
				if (stripped != flags &&
				    put_user(stripped,
					     (int __user *)optval_ptr) == 0) {
					vpnhide_dbg(
						"sys_setsockopt: stripped SO_TIMESTAMPING hw bits 0x%x→0x%x\n",
						flags, stripped);
					sdata->intercepted = true;
				}
			}
		}
	} else if (level == IPPROTO_IP) {
		if (optname == IP_MTU_DISCOVER) {
			int discover;
			if (optlen == sizeof(int)) {
				if (get_user(discover,
					     (int __user *)optval_ptr) == 0) {
					if (discover != IP_PMTUDISC_DONT) {
						if (put_user(IP_PMTUDISC_DONT,
							     (int __user *)
								     optval_ptr) ==
						    0) {
							vpnhide_dbg(
								"sys_setsockopt: spoofed IP_MTU_DISCOVER from %d to IP_PMTUDISC_DONT\n",
								discover);
							sdata->intercepted =
								true;
						}
					}
				}
			}
		}
	} else if (level == IPPROTO_IPV6) {
		if (optname == IPV6_MTU_DISCOVER) {
			int discover;
			if (optlen == sizeof(int)) {
				if (get_user(discover,
					     (int __user *)optval_ptr) == 0) {
					if (discover != IPV6_PMTUDISC_DONT) {
						if (put_user(IPV6_PMTUDISC_DONT,
							     (int __user *)
								     optval_ptr) ==
						    0) {
							vpnhide_dbg(
								"sys_setsockopt: spoofed IPV6_MTU_DISCOVER from %d to IPV6_PMTUDISC_DONT\n",
								discover);
							sdata->intercepted =
								true;
						}
					}
				}
			}
		}
	} else if (level == IPPROTO_UDP) {
		/*
		 * UDP_SEGMENT = 103: zero the segment size before the kernel
		 * reads it.  Setting gso_size to 0 disables GSO on this socket,
		 * turning the subsequent large send() into a plain (non-GSO) UDP
		 * datagram.  Without this, the kernel returns -EIO in
		 * udp_send_skb() because a tun/WireGuard device lacks
		 * CHECKSUM_PARTIAL support (skb->ip_summed != CHECKSUM_PARTIAL),
		 * which is the first condition gating GSO path entry.
		 * setsockopt(UDP_SEGMENT, 0) still succeeds (gso_size = 0 is
		 * valid), so the check's opt_ret >= 0 path continues normally.
		 */
		if (optname == 103 /* UDP_SEGMENT */ && optlen == sizeof(int)) {
			int zero = 0;

			if (put_user(zero, (int __user *)optval_ptr) == 0) {
				vpnhide_dbg(
					"sys_setsockopt: zeroed UDP_SEGMENT to block GSO probe uid=%u\n",
					from_kuid(&init_user_ns,
						  current_uid()));
				sdata->intercepted = true;
			}
		}
	}

	if (!sdata->override_ret && !sdata->intercepted)
		return 1;

	return 0;
}

static int sys_setsockopt_ret(struct kretprobe_instance *ri,
			      struct pt_regs *regs)
{
	struct sock_setsockopt_data *sdata = (void *)ri->data;
	if (sdata->intercepted) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      3);
	}
	if (sdata->override_ret) {
		regs_set_return_value(
			regs, sdata->deny_errno ? -sdata->deny_errno : 0);
	}
	return 0;
}

static struct kretprobe sys_setsockopt_krp = {
	.handler = sys_setsockopt_ret,
	.entry_handler = sys_setsockopt_entry,
	.data_size = sizeof(struct sock_setsockopt_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_setsockopt",
};

/* ================================================================== */
/*  Hook 2c: sock_getsockopt & sock_common_getsockopt — Bind Query    */
/*           Sabotage                                                 */
/*  Android source path:                                              */
/*    - sock_getsockopt: net/socket.c                                 */
/*    - sock_common_getsockopt: net/core/sock.c                       */
/*                                                                    */
/*  What it does:                                                     */
/*    Intercepts getsockopt calls for target UIDs to spoof socket     */
/*    options. If queried for SO_BINDTODEVICE or SO_BINDTOIFINDEX     */
/*    referencing the VPN, it returns empty/zero. It also overrides   */
/*    MTU/MSS sizes to match Wi-Fi/cellular defaults (e.g. 1500/1460).*/
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps can verify if their sockets are bound to the VPN    */
/*    device or detect the VPN tunnel via reduced MTU/MSS anomalies.  */
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
};

static int sock_getsockopt_entry(struct kretprobe_instance *ri,
				 struct pt_regs *regs)
{
	struct sock_getsockopt_data *data;
	struct socket *sock = (struct socket *)regs->regs[0];
	int level = (int)regs->regs[1];
	int optname = (int)regs->regs[2];

	if (!is_hook_active(HOOK_GETSOCKOPT))
		return 1;

	if (level != SOL_SOCKET && level != IPPROTO_IP &&
	    level != IPPROTO_IPV6 && level != IPPROTO_TCP)
		return 1;

	if (level == SOL_SOCKET && optname != SO_BINDTODEVICE &&
	    optname != SO_BINDTOIFINDEX)
		return 1;
	if (level == IPPROTO_IP && optname != IP_MTU &&
	    optname != IP_MTU_DISCOVER)
		return 1;
	if (level == IPPROTO_IPV6 && optname != IPV6_MTU &&
	    optname != IPV6_MTU_DISCOVER)
		return 1;
	if (level == IPPROTO_TCP && optname != TCP_MAXSEG &&
	    optname != TCP_INFO)
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->level = level;
	data->optname = optname;
	data->optval = (void __user *)regs->regs[3];
	data->optlen = (int __user *)regs->regs[4];
	data->net = sock && sock->sk ?
			    sock_net(sock->sk) :
			    (current->nsproxy ? current->nsproxy->net_ns :
						&init_net);

	return 0;
}

static int sock_getsockopt_ret(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	struct sock_getsockopt_data *data = (void *)ri->data;
	int ret = regs_return_value(regs);

	if (ret != 0)
		return 0;

	record_kmod_intercept(from_kuid(&init_user_ns, current_uid()), 3);

	if (data->level == IPPROTO_IP && data->optname == IP_MTU) {
		int mtu = 0;
		int len = 0;
		if (get_user(len, data->optlen) == 0 && len >= sizeof(int)) {
			if (get_user(mtu, (int __user *)data->optval) == 0) {
				if (mtu > 0 && mtu < 1500) {
					if (put_user(1500, (int __user *)data
								   ->optval) ==
					    0) {
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
			if (get_user(mtu, (int __user *)data->optval) == 0) {
				if (mtu > 0 && mtu < 1500) {
					if (put_user(1500, (int __user *)data
								   ->optval) ==
					    0) {
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
			if (get_user(discover, (int __user *)data->optval) ==
			    0) {
				if (discover == IP_PMTUDISC_DONT) {
					if (put_user(IP_PMTUDISC_DO,
						     (int __user *)data
							     ->optval) == 0) {
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
			if (get_user(discover, (int __user *)data->optval) ==
			    0) {
				if (discover == IPV6_PMTUDISC_DONT) {
					if (put_user(IPV6_PMTUDISC_DO,
						     (int __user *)data
							     ->optval) == 0) {
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
			if (get_user(mss, (int __user *)data->optval) == 0) {
				if (mss > 0 && mss < 1460) {
					if (put_user(1460, (int __user *)data
								   ->optval) ==
					    0) {
						vpnhide_dbg(
							"sock_getsockopt_ret: spoofed TCP_MAXSEG from %d to 1460\n",
							mss);
					}
				}
			}
		}
		return 0;
	}

	if (data->level == IPPROTO_TCP && data->optname == TCP_INFO) {
		/* Spoof MSS fields in tcp_info to hide VPN tunnel overhead.
		 * Only read/write the first 24 bytes (stable ABI subset):
		 * 8 flag bytes + rto + ato + snd_mss + rcv_mss. */
		struct {
			u8 _pre[8];
			u32 rto;
			u32 ato;
			u32 snd_mss;
			u32 rcv_mss;
		} ti;
		int len = 0;
		bool changed = false;

		if (get_user(len, data->optlen) != 0 || len < (int)sizeof(ti))
			return 0;
		if (copy_from_user(&ti, data->optval, sizeof(ti)))
			return 0;

		if (ti.snd_mss > 0 && ti.snd_mss < 1440) {
			ti.snd_mss = 1460;
			changed = true;
		}
		if (ti.rcv_mss > 0 && ti.rcv_mss < 1440) {
			ti.rcv_mss = 1460;
			changed = true;
		}
		if (changed) {
			if (copy_to_user(data->optval, &ti, sizeof(ti)) == 0)
				vpnhide_dbg(
					"sock_getsockopt_ret: spoofed TCP_INFO snd_mss/rcv_mss\n");
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

		if (len >= IFNAMSIZ)
			len = IFNAMSIZ - 1;

		if (copy_from_user(name, data->optval, len))
			return 0;
		name[len] = '\0';

		if (is_vpn_ifname(name)) {
			vpnhide_dbg(
				"sock_getsockopt_ret: spoofing empty SO_BINDTODEVICE (was %s)\n",
				name);

			if (put_user('\0', (char __user *)data->optval) == 0 &&
			    put_user(0, data->optlen) == 0) {
				/* Success */
			}
		}
	} else if (data->optname == SO_BINDTOIFINDEX) {
		int ifindex;

		if (get_user(ifindex, (int __user *)data->optval))
			return 0;

		if (ifindex <= 0)
			return 0;

		if (is_active_vpn_ifindex(ifindex)) {
			vpnhide_dbg(
				"sock_getsockopt_ret: spoofing SO_BINDTOIFINDEX %d to 0\n",
				ifindex);
			if (put_user(0, (int __user *)data->optval)) {
				/* error */
			}
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

/* ================================================================== */
/*  Hook 2c-primary: __arm64_sys_getsockopt — syscall-level hook      */
/*                                                                    */
/*  What it does:                                                     */
/*    Intercepts __arm64_sys_getsockopt syscalls directly. It handles */
/*    getsockopt queries coming from userspace. It avoids LTO-inlining */
/*    problems and kernel-internal sockets (e.g. eBPF or IPsec).      */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    If an GKI kernel inlines sock_getsockopt (due to LTO), then     */
/*    standard getsockopt hooks won't fire. This primary syscall hook */
/*    ensures getsockopt queries never escape spoofing, preventing the*/
/*    VPN from leaking via LTO-inlined paths.                         */
/*                                                                    */
/*  x0 = *pt_regs (userspace regs).                                   */
/*  Вызывается только из userspace → нет риска перехватить            */
/*  kernel-internal вызовы (IPsec, eBPF), в отличие от sk_getsockopt.*/
/*  Никогда не инлайнится LTO, в отличие от sock_getsockopt на 5.15. */
/* ================================================================== */

static bool sys_getsockopt_uses_wrapper;

static int sys_getsockopt_entry(struct kretprobe_instance *ri,
				struct pt_regs *regs)
{
	struct sock_getsockopt_data *data;
	int level, optname;
	void __user *optval;
	int __user *optlen;

	if (!is_hook_active(HOOK_GETSOCKOPT))
		return 1;

	if (sys_getsockopt_uses_wrapper) {
		struct pt_regs *user_regs = (struct pt_regs *)regs->regs[0];
		if (!user_regs ||
		    (unsigned long)user_regs < 0xFFFF000000000000ULL)
			return 1;
		level = (int)user_regs->regs[1];
		optname = (int)user_regs->regs[2];
		optval = (void __user *)user_regs->regs[3];
		optlen = (int __user *)user_regs->regs[4];
	} else {
		level = (int)regs->regs[1];
		optname = (int)regs->regs[2];
		optval = (void __user *)regs->regs[3];
		optlen = (int __user *)regs->regs[4];
	}

	if (level != SOL_SOCKET && level != IPPROTO_IP &&
	    level != IPPROTO_IPV6 && level != IPPROTO_TCP)
		return 1;

	if (level == SOL_SOCKET && optname != SO_BINDTODEVICE &&
	    optname != SO_BINDTOIFINDEX)
		return 1;
	if (level == IPPROTO_IP && optname != IP_MTU &&
	    optname != IP_MTU_DISCOVER)
		return 1;
	if (level == IPPROTO_IPV6 && optname != IPV6_MTU &&
	    optname != IPV6_MTU_DISCOVER)
		return 1;
	if (level == IPPROTO_TCP && optname != TCP_MAXSEG &&
	    optname != TCP_INFO)
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->level = level;
	data->optname = optname;
	data->optval = optval;
	data->optlen = optlen;
	data->net = current->nsproxy ? current->nsproxy->net_ns : &init_net;

	return 0;
}

static struct kretprobe sys_getsockopt_krp = {
	.entry_handler = sys_getsockopt_entry,
	.handler = sock_getsockopt_ret,
	.data_size = sizeof(struct sock_getsockopt_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_getsockopt",
};

static int sk_getsockopt_entry(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	struct sock_getsockopt_data *data;
	struct sock *sk = (struct sock *)regs->regs[0];
	bool is_kernel = (bool)(regs->regs[4] & 1);
	int level = (int)regs->regs[1];
	int optname = (int)regs->regs[2];

	if (!is_hook_active(HOOK_GETSOCKOPT))
		return 1;

	if (is_kernel)
		return 1;

	if (level != SOL_SOCKET && level != IPPROTO_IP &&
	    level != IPPROTO_IPV6 && level != IPPROTO_TCP)
		return 1;

	if (level == SOL_SOCKET && optname != SO_BINDTODEVICE &&
	    optname != SO_BINDTOIFINDEX)
		return 1;
	if (level == IPPROTO_IP && optname != IP_MTU &&
	    optname != IP_MTU_DISCOVER)
		return 1;
	if (level == IPPROTO_IPV6 && optname != IPV6_MTU &&
	    optname != IPV6_MTU_DISCOVER)
		return 1;
	if (level == IPPROTO_TCP && optname != TCP_MAXSEG &&
	    optname != TCP_INFO)
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->level = level;
	data->optname = optname;
	data->optval = (void __user *)regs->regs[3];
	data->optlen = (int __user *)regs->regs[5];
	data->net =
		sk ? sock_net(sk) :
		     (current->nsproxy ? current->nsproxy->net_ns : &init_net);

	return 0;
}

static struct kretprobe sk_getsockopt_krp = {
	.entry_handler = sk_getsockopt_entry,
	.handler = sock_getsockopt_ret,
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.data_size = sizeof(struct sock_getsockopt_data),
	.kp.symbol_name = "sk_getsockopt",
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
/*  What it does:                                                     */
/*    Intercepts the netlink link info filler rtnl_fill_ifinfo. If    */
/*    the caller is a target UID and the interface is an active VPN,  */
/*    it trims the skb back to its pre-fill length. The netlink       */
/*    subsystem treats it as a zero-byte successful write and skips  */
/*    the interface entirely without returning an error.              */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Netlink link dumps (e.g., getifaddrs() or modern network state  */
/*    libraries) will receive the VPN interface entry, fully leaking  */
/*    its existence, state, and properties.                           */
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

	if (!is_hook_active(HOOK_RTNL_FILL))
		return 1;

	if (!is_target_uid())
		return 1;

	dev = (struct net_device *)regs->regs[1];
	if (!dev || !is_active_vpn_ifindex(dev->ifindex))
		return 1;

	data = (void *)ri->data;
	data->skb = (struct sk_buff *)regs->regs[0];
	data->saved_len = data->skb ? data->skb->len : 0;
	data->should_filter = true;

	vpnhide_dbg("rtnl_fill_entry: uid=%u target=1 iface=%s -> filter\n",
		    from_kuid(&init_user_ns, current_uid()), dev->name);

	return 0;
}

static int rtnl_fill_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct rtnl_fill_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	vpnhide_dbg("rtnl_fill_ret: trimming skb %u -> %u\n", data->skb->len,
		    data->saved_len);
	record_kmod_intercept(from_kuid(&init_user_ns, current_uid()), 1);
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
/*  What it does:                                                     */
/*    Filters netlink RTM_GETADDR responses for IPv6 addresses        */
/*    belonging to the VPN interface. Uses the skb_trim method to     */
/*    silently discard the written IPv6 address details.              */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    If hidden in RTM_NEWLINK but not here, the VPN IPv6 address will*/
/*    still leak in RTM_GETADDR responses. Android's libc (bionic)    */
/*    uses these addresses to reconstruct a dummy interface entry,    */
/*    exposing the VPN's existence.                                   */
/*                                                                    */
/*  inet6_fill_ifaddr(struct sk_buff *skb, struct inet6_ifaddr *ifa,  */
/*                    struct inet6_fill_args *args)                   */
/*  arm64: x0=skb, x1=ifa                                             */
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

	if (!is_hook_active(HOOK_INET6_FILL))
		return 1;

	if (!is_target_uid())
		return 1;

	ifa = (struct inet6_ifaddr *)regs->regs[1];
	if (!ifa || !ifa->idev || !ifa->idev->dev ||
	    !is_active_vpn_ifindex(ifa->idev->dev->ifindex))
		return 1;

	data = (void *)ri->data;
	data->skb = (struct sk_buff *)regs->regs[0];
	data->saved_len = data->skb ? data->skb->len : 0;
	data->should_filter = true;
	vpnhide_dbg("inet6_fill_entry: uid=%u iface=%s -> filter\n",
		    from_kuid(&init_user_ns, current_uid()),
		    ifa->idev->dev->name);

	return 0;
}

static int inet6_fill_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct inet6_fill_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	vpnhide_dbg("inet6_fill_ret: trimming skb %u -> %u\n", data->skb->len,
		    data->saved_len);
	record_kmod_intercept(from_kuid(&init_user_ns, current_uid()), 1);
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
/*  What it does:                                                     */
/*    Filters netlink RTM_GETADDR responses for IPv4 addresses        */
/*    belonging to the VPN interface. Uses the skb_trim method to     */
/*    silently discard the written IPv4 address details.              */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    If hidden in RTM_NEWLINK but not here, the VPN IPv4 address will*/
/*    still leak in RTM_GETADDR responses. Android's libc (bionic)    */
/*    uses these addresses to reconstruct a dummy interface entry,    */
/*    exposing the VPN's existence.                                   */
/*                                                                    */
/*  inet_fill_ifaddr(struct sk_buff *skb, struct in_ifaddr *ifa,      */
/*                   struct inet_fill_args *args)                     */
/*  arm64: x0=skb, x1=ifa                                             */
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

	if (!is_hook_active(HOOK_INET_FILL))
		return 1;

	if (!is_target_uid())
		return 1;

	ifa = (struct in_ifaddr *)regs->regs[1];
	if (!ifa || !ifa->ifa_dev || !ifa->ifa_dev->dev ||
	    !is_active_vpn_ifindex(ifa->ifa_dev->dev->ifindex))
		return 1;

	data = (void *)ri->data;
	data->skb = (struct sk_buff *)regs->regs[0];
	data->saved_len = data->skb ? data->skb->len : 0;
	data->should_filter = true;
	vpnhide_dbg("inet_fill_entry: uid=%u iface=%s -> filter\n",
		    from_kuid(&init_user_ns, current_uid()),
		    ifa->ifa_dev->dev->name);

	return 0;
}

static int inet_fill_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct inet_fill_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	vpnhide_dbg("inet_fill_ret: trimming skb %u -> %u\n", data->skb->len,
		    data->saved_len);
	record_kmod_intercept(from_kuid(&init_user_ns, current_uid()), 1);
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
/*  What it does:                                                     */
/*    Intercepts output of /proc/net/route for target UIDs and        */
/*    compacts out any routing entries referencing the VPN interface.  */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps reading /proc/net/route can parse active routes to  */
/*    find the VPN interface name and destination gateway.            */
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
};

static int fib_route_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data;
	if (!is_hook_active(HOOK_FIB_ROUTE))
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->seq = (struct seq_file *)regs->regs[0];
	data->start_count = data->seq ? data->seq->count : 0;

	vpnhide_dbg("fib_route_entry: uid=%u target=1\n",
		    from_kuid(&init_user_ns, current_uid()));

	return 0;
}

static int fib_route_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data = (void *)ri->data;
	struct seq_file *seq = data->seq;
	char *buf, *src, *dst, *end;
	char ifname[IFNAMSIZ];
	int j;

	if (!seq || !seq->buf)
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
/*  What it does:                                                     */
/*    Filters IPv4 route dumps filled via netlink (RTM_GETROUTE). If  */
/*    the route references the VPN interface index, the entry is      */
/*    trimmed and hidden from netlink responses for target UIDs.      */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps can use netlink RTM_GETROUTE queries to scan the    */
/*    routing tables and discover the default gateway or routes       */
/*    assigned to the VPN interface.                                  */
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
	struct net_device *dev = NULL;

	if (!is_hook_active(HOOK_FIB_DUMP))
		return 1;

	if (!is_target_uid())
		return 1;

	/* GKI 5.10 and 5.15+ both pass struct fib_rt_info* in x4 (regs->regs[4]) */
	fri = (struct fib_rt_info *)regs->regs[4];
	if (fri &&
	    copy_from_kernel_nofault(&fri_copy, fri, sizeof(fri_copy)) == 0) {
		fi = fri_copy.fi;
	}

	if (!fi)
		return 1;

	rcu_read_lock();
	dev = vpnhide_get_fib_info_dev(fi);
	if (dev && is_active_vpn_ifindex(dev->ifindex)) {
		data = (void *)ri->data;
		data->skb = (struct sk_buff *)regs->regs[0];
		data->saved_len = data->skb ? data->skb->len : 0;
		data->should_filter = true;
		vpnhide_dbg("fib_dump_entry: hiding route via %s\n", dev->name);
		rcu_read_unlock();
		return 0;
	}
	rcu_read_unlock();

	return 1;
}

static int fib_dump_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_dump_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	if (regs_return_value(regs) >= 0) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      1);
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
/*  What it does:                                                     */
/*    Filters policy routing rule dumps filled via netlink (RTM_GETRULE).*/
/*    If a policy rule is bound to the target UID and points to a VPN  */
/*    routing table, it trims the skb to hide this rule from target UIDs.*/
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps can verify their network routing path by dumping    */
/*    routing rules. They will see system-level rules directing their  */
/*    UID's traffic to custom VPN tables, indicating a VPN is active.  */
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
	bool filter = false;

	if (!is_hook_active(HOOK_FIB_RULE_FILL))
		return 1;

	if (!is_target_uid())
		return 1;

	rule = (struct fib_rule *)regs->regs[1];
	if (!rule)
		return 1;

	my_uid = from_kuid(&init_user_ns, current_uid());

	rcu_read_lock();
	if ((rule->iifname[0] != '\0' && is_vpn_ifname(rule->iifname)) ||
	    (rule->oifname[0] != '\0' && is_vpn_ifname(rule->oifname))) {
		filter = true;
		vpnhide_dbg(
			"fib_rule_fill_entry: hiding rule via VPN interface %s / %s\n",
			rule->iifname, rule->oifname);
	} else {
		uid_t start = from_kuid(&init_user_ns, rule->uid_range.start);
		uid_t end = from_kuid(&init_user_ns, rule->uid_range.end);
		/*
		 * Hide any split-routing rule that touches app UID space (>= 10000).
		 * We check `end` rather than `start` to also catch rules like [0..app_uid]
		 * where the VPN routes from UID 0 up to the target app's UID.
		 * Catch-all rules [0..UINT_MAX] are excluded via end != ~0.
		 */
		if (((start >= 10000 || end >= 10000) ||
		     is_target_uid_val(start) || is_target_uid_val(end)) &&
		    end != (uid_t)~0) {
			if (rule->table != 254 && rule->table != 255 &&
			    rule->table != 253 && rule->table > 100) {
				filter = true;
				vpnhide_dbg(
					"fib_rule_fill_entry: hiding UID split-routing rule range %u-%u, table %u\n",
					start, end, rule->table);
			}
		}
	}
	rcu_read_unlock();

	if (!filter)
		return 1;

	data = (void *)ri->data;
	data->skb = (struct sk_buff *)regs->regs[0];
	data->saved_len = data->skb ? data->skb->len : 0;
	data->should_filter = true;

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
				      1);
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
/*  What it does:                                                     */
/*    Filters IPv6 routing tables dumps filled via netlink (RTM_GETROUTE).*/
/*    If the route outputs to the VPN interface, the netlink entry is  */
/*    trimmed and discarded for target UIDs.                          */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps can verify their network routing path by dumping    */
/*    IPv6 routes, leaking the gateway address or VPN output interface.*/
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
	bool is_vpn = false;
	const char *ifname = NULL;

	if (!is_hook_active(HOOK_RT6_FILL))
		return 1;

	if (!is_target_uid())
		return 1;

	rt = (struct fib6_info *)regs->regs[2];
	dst = (struct dst_entry *)regs->regs[3];

	rcu_read_lock();
	if (rt) {
		struct net_device *dev = rt->fib6_nh->nh_common.nhc_dev;
		if (dev && is_active_vpn_ifindex(dev->ifindex)) {
			is_vpn = true;
			ifname = dev->name;
		}
	} else if (dst && dst->dev &&
		   is_active_vpn_ifindex(dst->dev->ifindex)) {
		is_vpn = true;
		ifname = dst->dev->name;
	}

	if (is_vpn) {
		data = (void *)ri->data;
		data->skb = (struct sk_buff *)regs->regs[1];
		data->saved_len = data->skb ? data->skb->len : 0;
		data->should_filter = true;
		vpnhide_dbg("rt6_fill_entry: hiding IPv6 route via %s\n",
			    ifname);
		rcu_read_unlock();
		return 0;
	}
	rcu_read_unlock();

	return 1;
}

static int rt6_fill_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct rt6_fill_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	if (regs_return_value(regs) >= 0) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      1);
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
/*  What it does:                                                     */
/*    Filters /proc/net/ipv6_route for target UIDs to remove routing  */
/*    entries associated with the VPN interface name.                 */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps reading /proc/net/ipv6_route will see active IPv6   */
/*    routes over the VPN tunnel, revealing the VPN interface.        */
/*                                                                    */
/*  ipv6_route_seq_show(seq, v) is the IPv6 equivalent of hook 6.     */
/*  The interface name is the LAST field in the line.                 */
/* ================================================================== */

static int ipv6_route_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data;
	if (!is_hook_active(HOOK_IPV6_ROUTE))
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->seq = (struct seq_file *)regs->regs[0];
	data->start_count = data->seq ? data->seq->count : 0;

	vpnhide_dbg("ipv6_route_entry: uid=%u target=1\n",
		    from_kuid(&init_user_ns, current_uid()));

	return 0;
}

static int ipv6_route_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data = (void *)ri->data;
	struct seq_file *seq = data->seq;
	char *buf, *src, *dst, *end;
	char ifname[IFNAMSIZ];
	int j;

	if (!seq || !seq->buf)
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
/*  What it does:                                                     */
/*    Filters netlink responses for single IPv4 route queries         */
/*    (RTM_GETROUTE). If the lookup result points to the VPN          */
/*    interface, the response skb is trimmed and skipped for target UIDs.*/
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps querying routing for specific IP addresses will     */
/*    receive route entries pointing directly to the VPN interface,   */
/*    leaking that their connections are being tunneled.              */
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
	bool is_vpn = false;

	if (!is_hook_active(HOOK_RT_FILL))
		return 1;

	if (!is_target_uid())
		return 1;

	rt = (struct rtable *)regs->regs[3];
	skb = (struct sk_buff *)regs->regs[7];

	if (rt) {
		if (copy_from_kernel_nofault(&dev_ptr, &rt->dst.dev,
					     sizeof(dev_ptr)) == 0 &&
		    dev_ptr) {
			dev = dev_ptr;
		}
	}

	rcu_read_lock();
	if (dev && is_active_vpn_ifindex(dev->ifindex)) {
		is_vpn = true;
	}
	rcu_read_unlock();

	if (!is_vpn)
		return 1;

	data = (void *)ri->data;
	data->should_filter = true;
	data->skb = NULL;
	data->saved_len = 0;

	if (skb) {
		if (copy_from_kernel_nofault(&temp_len, &skb->len,
					     sizeof(temp_len)) == 0) {
			data->skb = skb;
			data->saved_len = temp_len;
		}
	}

	vpnhide_dbg("rt_fill_entry: hiding route via index %d\n", dev->ifindex);

	return 0;
}

static int rt_fill_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct rt_fill_data *data = (void *)ri->data;

	if (!data->should_filter || !data->skb)
		return 0;

	if (regs_return_value(regs) >= 0) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      1);
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
	atomic_inc(&vpnhide_config_generation);
	wake_up_interruptible(&vpnhide_config_wait);
	return 0;
}

static int update_lsposed_targets(uid_t *uids, int count)
{
	struct vpnhide_targets *new_t, *old_t;

	new_t = kzalloc(sizeof(*new_t), GFP_KERNEL);
	if (!new_t)
		return -ENOMEM;

	new_t->count = count;
	if (count > 0)
		memcpy(new_t->uids, uids, count * sizeof(uid_t));

	spin_lock(&lsposed_targets_update_lock);
	old_t = rcu_dereference_protected(
		global_lsposed_targets,
		lockdep_is_held(&lsposed_targets_update_lock));
	rcu_assign_pointer(global_lsposed_targets, new_t);
	spin_unlock(&lsposed_targets_update_lock);

	if (old_t) {
		synchronize_rcu();
		kfree(old_t);
	}

	vpnhide_dbg("LSPosed targets updated: %d UIDs\n", count);
	atomic_inc(&vpnhide_config_generation);
	wake_up_interruptible(&vpnhide_config_wait);
	return 0;
}

static char java_stats_buf[4096];
static DEFINE_MUTEX(java_stats_lock);
static char java_status_buf[256];
static DEFINE_MUTEX(java_status_lock);

static char global_cover_ifname[IFNAMSIZ];
static DEFINE_SPINLOCK(cover_ifname_lock);

struct vpnhide_dev_reader {
	unsigned long generation;
	char *buf;
	size_t buf_len;
	size_t read_pos;
};

static int vpnhide_dev_open(struct inode *inode, struct file *file)
{
	struct vpnhide_dev_reader *reader;

	reader = kzalloc(sizeof(*reader), GFP_KERNEL);
	if (!reader)
		return -ENOMEM;

	file->private_data = reader;
	return 0;
}

static int vpnhide_dev_release(struct inode *inode, struct file *file)
{
	struct vpnhide_dev_reader *reader = file->private_data;

	if (reader) {
		kvfree(reader->buf);
		kfree(reader);
	}
	return 0;
}

static ssize_t vpnhide_dev_read(struct file *file, char __user *buf,
				size_t count, loff_t *ppos)
{
	struct vpnhide_dev_reader *reader = file->private_data;

	if (!reader)
		return -EINVAL;

	if (reader->read_pos >= reader->buf_len) {
		unsigned long gen =
			(unsigned long)atomic_read(&vpnhide_config_generation);
		if (reader->generation >= gen) {
			if (from_kuid(&init_user_ns, current_uid()) != 1000)
				return 0; /* Return EOF for debug/one-off readers like cat */
			if (file->f_flags & O_NONBLOCK)
				return -EAGAIN;
			if (wait_event_interruptible(
				    vpnhide_config_wait,
				    reader->generation <
					    (unsigned long)atomic_read(
						    &vpnhide_config_generation)))
				return -ERESTARTSYS;
		}

		kvfree(reader->buf);
		/* Allocate a large buffer (64KB) via kvmalloc to safely handle thousands of targets. 
		 * Uses scnprintf instead of snprintf to prevent buffer overflow/underflow. */
		reader->buf = kvmalloc(65536, GFP_KERNEL);
		if (!reader->buf)
			return -ENOMEM;

		{
			int offset = 0;
			struct vpnhide_targets *lt;
			struct vpnhide_iface_prefixes *ip;
			int i;

			offset += scnprintf(reader->buf + offset,
					    65536 - offset,
					    "java_hook_mask: %u\n",
					    READ_ONCE(java_hooks_mask));

			offset += scnprintf(
				reader->buf + offset, 65536 - offset,
				"java_stats_clear_gen: %d\n",
				atomic_read(&java_stats_clear_generation));

			offset += scnprintf(reader->buf + offset,
					    65536 - offset,
					    "debug_enabled: %d\n",
					    READ_ONCE(debug_enabled));

			rcu_read_lock();
			lt = rcu_dereference(global_lsposed_targets);
			offset += scnprintf(reader->buf + offset,
					    65536 - offset, "lsposed_targets:");
			if (lt) {
				for (i = 0; i < lt->count; i++) {
					offset +=
						scnprintf(reader->buf + offset,
							  65536 - offset, " %u",
							  lt->uids[i]);
				}
			}
			offset += scnprintf(reader->buf + offset,
					    65536 - offset, "\n");

			ip = rcu_dereference(global_iface_prefixes);
			offset += scnprintf(reader->buf + offset,
					    65536 - offset, "iface_prefixes:");
			if (ip) {
				for (i = 0; i < ip->count; i++) {
					offset +=
						scnprintf(reader->buf + offset,
							  65536 - offset, " %s",
							  ip->prefixes[i]);
				}
			}
			offset += scnprintf(reader->buf + offset,
					    65536 - offset, "\n");
			rcu_read_unlock();

			spin_lock(&cover_ifname_lock);
			offset += scnprintf(reader->buf + offset,
					    65536 - offset, "cover_iface: %s\n",
					    global_cover_ifname[0] ?
						    global_cover_ifname :
						    "none");
			spin_unlock(&cover_ifname_lock);

			offset += scnprintf(reader->buf + offset,
					    65536 - offset, "\n");

			reader->buf_len = offset;
		}

		reader->generation =
			(unsigned long)atomic_read(&vpnhide_config_generation);
		reader->read_pos = 0;
	}

	{
		size_t to_copy = min(count, reader->buf_len - reader->read_pos);
		if (copy_to_user(buf, reader->buf + reader->read_pos, to_copy))
			return -EFAULT;
		reader->read_pos += to_copy;
		return to_copy;
	}
}

static ssize_t vpnhide_dev_write(struct file *file, const char __user *buf,
				 size_t count, loff_t *ppos)
{
	char *kbuf;
	if (count > 4096)
		return -EINVAL;

	kbuf = kmalloc(count + 1, GFP_KERNEL);
	if (!kbuf)
		return -ENOMEM;

	if (copy_from_user(kbuf, buf, count)) {
		kfree(kbuf);
		return -EFAULT;
	}
	kbuf[count] = '\0';

	if (strncmp(kbuf, "stats:", 6) == 0) {
		mutex_lock(&java_stats_lock);
		strncpy(java_stats_buf, kbuf + 6, sizeof(java_stats_buf) - 1);
		java_stats_buf[sizeof(java_stats_buf) - 1] = '\0';
		mutex_unlock(&java_stats_lock);
	} else if (strncmp(kbuf, "status:", 7) == 0) {
		mutex_lock(&java_status_lock);
		strncpy(java_status_buf, kbuf + 7, sizeof(java_status_buf) - 1);
		java_status_buf[sizeof(java_status_buf) - 1] = '\0';
		mutex_unlock(&java_status_lock);
	} else if (strcmp(kbuf, "clear_stats") == 0) {
		mutex_lock(&java_stats_lock);
		java_stats_buf[0] = '\0';
		mutex_unlock(&java_stats_lock);
		atomic_inc(&java_stats_clear_generation);
		atomic_inc(&vpnhide_config_generation);
		wake_up_interruptible(&vpnhide_config_wait);
	} else if (strncmp(kbuf, "cover_iface:", 12) == 0) {
		char *val = kbuf + 12;
		size_t len = strlen(val);
		if (len > 0 && val[len - 1] == '\n') {
			val[len - 1] = '\0';
			len--;
		}
		spin_lock(&cover_ifname_lock);
		if (len == 0 || strcmp(val, "none") == 0) {
			global_cover_ifname[0] = '\0';
		} else {
			strncpy(global_cover_ifname, val, IFNAMSIZ - 1);
			global_cover_ifname[IFNAMSIZ - 1] = '\0';
		}
		spin_unlock(&cover_ifname_lock);
		atomic_inc(&vpnhide_config_generation);
		wake_up_interruptible(&vpnhide_config_wait);
	}

	kfree(kbuf);
	return count;
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
	case VH_SET_LSPOSED_TARGETS:
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
		else if (cmd == VH_SET_LSPOSED_TARGETS)
			ret = update_lsposed_targets(kdata->uids, kdata->count);
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

	case VH_GET_TARGETS:
	case VH_GET_LSPOSED_TARGETS: {
		struct vpnhide_targets *t;
		struct vpnhide_ioctl_data *kdata;

		kdata = kzalloc(sizeof(*kdata), GFP_KERNEL);
		if (!kdata)
			return -ENOMEM;

		rcu_read_lock();
		if (cmd == VH_GET_TARGETS)
			t = rcu_dereference(global_targets);
		else
			t = rcu_dereference(global_lsposed_targets);
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

		spin_lock(&iface_prefixes_lock);
		old_p = rcu_dereference_protected(
			global_iface_prefixes,
			lockdep_is_held(&iface_prefixes_lock));
		rcu_assign_pointer(global_iface_prefixes, new_p);
		spin_unlock(&iface_prefixes_lock);

		if (old_p) {
			synchronize_rcu();
			kfree(old_p);
		}

		kfree(idata);
		atomic_inc(&vpnhide_config_generation);
		wake_up_interruptible(&vpnhide_config_wait);
		ret = 0;
		break;
	}

	case VH_SET_JAVA_HOOK_MASK:
		if (get_user(val, (unsigned int __user *)arg))
			return -EFAULT;
		WRITE_ONCE(java_hooks_mask, val);
		atomic_inc(&vpnhide_config_generation);
		wake_up_interruptible(&vpnhide_config_wait);
		ret = 0;
		break;

	case VH_GET_JAVA_HOOK_MASK:
		val = READ_ONCE(java_hooks_mask);
		if (put_user(val, (unsigned int __user *)arg))
			return -EFAULT;
		ret = 0;
		break;

	case VH_SET_SPOOF_IP: {
		struct vpnhide_spoof_ip sip;
		if (copy_from_user(&sip, (void __user *)arg, sizeof(sip)))
			return -EFAULT;
		ret = update_spoof_ip(&sip);
		if (ret == 0) {
			vpnhide_dbg(
				"ioctl: updated spoof IP: IPv4=%pI4 (%d), IPv6=%pI6c (%d)\n",
				&sip.ipv4_addr, sip.has_ipv4, sip.ipv6_addr,
				sip.has_ipv6);
		}
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

	case VH_GET_IFACE_PREFIXES: {
		struct vpnhide_iface_ioctl_data *idata;
		struct vpnhide_iface_prefixes *p;

		idata = kzalloc(sizeof(*idata), GFP_KERNEL);
		if (!idata)
			return -ENOMEM;

		rcu_read_lock();
		p = rcu_dereference(global_iface_prefixes);
		if (p) {
			idata->count = p->count;
			memcpy(idata->prefixes, p->prefixes,
			       sizeof(idata->prefixes));
		}
		rcu_read_unlock();

		if (copy_to_user((void __user *)arg, idata, sizeof(*idata))) {
			kfree(idata);
			return -EFAULT;
		}

		kfree(idata);
		ret = 0;
		break;
	}

	case VH_SET_VPN_IFINDEXES: {
		struct vpnhide_vpn_ifindexes *idata;
		struct vpnhide_active_vpns *new_vpns, *old_vpns;

		idata = kmalloc(sizeof(*idata), GFP_KERNEL);
		if (!idata)
			return -ENOMEM;

		if (copy_from_user(idata, (void __user *)arg, sizeof(*idata))) {
			kfree(idata);
			return -EFAULT;
		}

		if (idata->count < 0 || idata->count > MAX_ACTIVE_VPNS) {
			kfree(idata);
			return -EINVAL;
		}

		new_vpns = kzalloc(sizeof(*new_vpns), GFP_KERNEL);
		if (!new_vpns) {
			kfree(idata);
			return -ENOMEM;
		}

		new_vpns->count = idata->count;
		memcpy(new_vpns->vpns, idata->vpns, sizeof(new_vpns->vpns));

		spin_lock(&active_vpns_lock);
		old_vpns = rcu_dereference_protected(
			global_active_vpns, lockdep_is_held(&active_vpns_lock));
		rcu_assign_pointer(global_active_vpns, new_vpns);
		spin_unlock(&active_vpns_lock);

		vh_rebuild_name_cache(idata);

		if (old_vpns) {
			synchronize_rcu();
			kfree(old_vpns);
		}

		kfree(idata);
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
			u32 ioctl_sum = 0, netlink_sum = 0, proc_sum = 0,
			    sockopt_sum = 0, connect_sum = 0, getname_sum = 0;
			for (b = 0; b < BUCKETS_COUNT; b++) {
				if (now_min - kmod_stats[i].bucket_times[b] <
				    BUCKETS_COUNT) {
					ioctl_sum +=
						kmod_stats[i].ioctl_counts[b];
					netlink_sum +=
						kmod_stats[i].netlink_counts[b];
					proc_sum +=
						kmod_stats[i].proc_counts[b];
					sockopt_sum +=
						kmod_stats[i].sockopt_counts[b];
					connect_sum +=
						kmod_stats[i].connect_counts[b];
					getname_sum +=
						kmod_stats[i].getname_counts[b];
				}
			}
			if (ioctl_sum > 0 || netlink_sum > 0 || proc_sum > 0 ||
			    sockopt_sum > 0 || connect_sum > 0 ||
			    getname_sum > 0) {
				sdata->stats[active_count].uid =
					kmod_stats[i].uid;
				sdata->stats[active_count].ioctl_count =
					ioctl_sum;
				sdata->stats[active_count].netlink_count =
					netlink_sum;
				sdata->stats[active_count].proc_count =
					proc_sum;
				sdata->stats[active_count].sockopt_count =
					sockopt_sum;
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

	case VH_GET_JAVA_STATS: {
		mutex_lock(&java_stats_lock);
		if (copy_to_user((void __user *)arg, java_stats_buf,
				 sizeof(java_stats_buf))) {
			mutex_unlock(&java_stats_lock);
			return -EFAULT;
		}
		mutex_unlock(&java_stats_lock);
		ret = 0;
		break;
	}

	case VH_GET_HOOK_STATUS: {
		mutex_lock(&java_status_lock);
		if (copy_to_user((void __user *)arg, java_status_buf,
				 sizeof(java_status_buf))) {
			mutex_unlock(&java_status_lock);
			return -EFAULT;
		}
		mutex_unlock(&java_status_lock);
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
	.open = vpnhide_dev_open,
	.release = vpnhide_dev_release,
	.read = vpnhide_dev_read,
	.write = vpnhide_dev_write,
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
/*  What it does:                                                     */
/*    Intercepts connect syscalls (at security_socket_connect). If    */
/*    the target app tries to connect to loopback (127.0.0.1 / ::1)   */
/*    on specific ports used by local proxies or VPN services, it     */
/*    forces -ECONNREFUSED to hide the local proxy/service port.       */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps can connect directly to local proxy servers or local*/
/*    DNS resolvers run by the VPN client on loopback, allowing them  */
/*    to verify proxy connectivity and bypass/detect the tunnel.      */
/*                                                                    */
/*  security_socket_connect(struct socket *sock,                      */
/*                          struct sockaddr *address, int addrlen)    */
/*  arm64: x1=address                                                 */
/* ================================================================== */

static bool sys_connect_uses_wrapper;

struct socket_connect_data {
	bool should_block;
	bool intercepted;
};

static struct socket *resolve_sock_addr(struct pt_regs *regs, bool uses_wrapper,
					struct sockaddr *uaddr_buf,
					int max_uaddr_sz,
					struct sockaddr **out_addr,
					bool *put_needed, int *out_fd)
{
	int fd, err;
	struct socket *sock = NULL;
	*put_needed = false;
	*out_addr = NULL;

	if (uses_wrapper) {
		struct pt_regs *user_regs = (struct pt_regs *)regs->regs[0];
		if (user_regs &&
		    (unsigned long)user_regs >= 0xFFFF000000000000ULL) {
			int addrlen = (int)user_regs->regs[2];
			int copy_sz = min_t(int, addrlen, max_uaddr_sz);
			fd = (int)user_regs->regs[0];
			if (copy_sz > 0 &&
			    copy_from_user(uaddr_buf,
					   (void __user *)user_regs->regs[1],
					   copy_sz) == 0) {
				*out_addr = uaddr_buf;
			}
			sock = sockfd_lookup(fd, &err);
			if (sock)
				*put_needed = true;
			*out_fd = fd;
		}
	} else {
		sock = (struct socket *)regs->regs[0];
		*out_addr = (struct sockaddr *)regs->regs[1];
		*out_fd = -1;
	}
	return sock;
}

static bool should_block_port(const struct vpnhide_uid_port_rules *urules,
			      unsigned short port, unsigned char proto)
{
	int i;
	for (i = 0; i < urules->rule_count; i++) {
		const struct vpnhide_port_rule *r = &urules->rules[i];
		if (port >= r->start_port && port <= r->end_port) {
			if (r->protocol == VH_PROTO_BOTH ||
			    r->protocol == proto) {
				return true;
			}
		}
	}
	return false;
}

static int socket_connect_entry(struct kretprobe_instance *ri,
				struct pt_regs *regs)
{
	struct socket_connect_data *data;
	struct socket *sock = NULL;
	struct sockaddr *addr = NULL;
	struct sockaddr_storage uaddr_buf;
	uid_t uid = from_kuid(&init_user_ns, current_uid());
	struct vpnhide_port_targets *t;
	struct vpnhide_uid_port_rules *urules = NULL;
	int fd = -1;
	bool put_needed = false;
	int i;

	if (!is_hook_active(HOOK_CONNECT))
		return 1;

	data = (void *)ri->data;
	data->should_block = false;
	data->intercepted = false;

	sock = resolve_sock_addr(regs, sys_connect_uses_wrapper,
				 (struct sockaddr *)&uaddr_buf,
				 sizeof(uaddr_buf), &addr, &put_needed, &fd);

	if (sys_connect_uses_wrapper && !sock)
		return 0;

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
		if (put_needed)
			sockfd_put(sock);
		return 1;
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

			if (should_block_port(urules, port, proto)) {
				data->should_block = true;
				if (sys_connect_uses_wrapper) {
					struct pt_regs *user_regs =
						(struct pt_regs *)regs->regs[0];
					if (user_regs) {
						user_regs->regs[1] = 0;
						data->intercepted = true;
					}
				}
				vpnhide_dbg(
					"socket_connect: blocking IPv4 port %u (%s) for uid=%u\n",
					port,
					(proto == VH_PROTO_TCP) ? "TCP" : "UDP",
					uid);
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

			if (should_block_port(urules, port, proto)) {
				data->should_block = true;
				if (sys_connect_uses_wrapper) {
					struct pt_regs *user_regs =
						(struct pt_regs *)regs->regs[0];
					if (user_regs) {
						user_regs->regs[1] = 0;
						data->intercepted = true;
					}
				}
				vpnhide_dbg(
					"socket_connect: blocking IPv6 port %u (%s) for uid=%u\n",
					port,
					(proto == VH_PROTO_TCP) ? "TCP" : "UDP",
					uid);
			}
		}
	}
	rcu_read_unlock();

	if (put_needed)
		sockfd_put(sock);

	if (!data->should_block)
		return 1;

	return 0;
}

static int socket_connect_ret(struct kretprobe_instance *ri,
			      struct pt_regs *regs)
{
	struct socket_connect_data *data = (void *)ri->data;
	int retval = regs_return_value(regs);

	if (data->should_block) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      4);
		if (sys_connect_uses_wrapper) {
			if (data->intercepted && retval == -EFAULT) {
				regs_set_return_value(regs, -ECONNREFUSED);
			}
		} else {
			regs_set_return_value(regs, -ECONNREFUSED);
		}
	}

	return 0;
}

static struct kretprobe socket_connect_krp = {
	.handler = socket_connect_ret,
	.entry_handler = socket_connect_entry,
	.data_size = sizeof(struct socket_connect_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_connect",
};

/* ================================================================== */
/*  Hook 12b: security_socket_bind — Loopback Port Bind Spoofing      */
/*  Android source path: security/security.c                          */
/*                                                                    */
/*  What it does:                                                     */
/*    Intercepts bind calls (at security_socket_bind). If a target    */
/*    app tries to bind to a specific protected port on loopback      */
/*    (such as a port used by a local proxy), it silently rewrites    */
/*    the port argument to 0. The kernel binds to a random free       */
/*    ephemeral port, succeeding without throwing EADDRINUSE.          */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps can try binding sockets to specific proxy ports.    */
/*    If they get EADDRINUSE, they immediately know a local proxy is  */
/*    running on that port, which is a common VPN detection heuristic.*/
/*                                                                    */
/*  security_socket_bind(struct socket *sock,                         */
/*                       struct sockaddr *address, int addrlen)       */
/*  arm64: x1=address       */
/* ================================================================== */

static bool sys_bind_uses_wrapper;

static int socket_bind_entry(struct kretprobe_instance *ri,
			     struct pt_regs *regs)
{
	struct socket *sock = NULL;
	struct sockaddr *addr = NULL;
	struct sockaddr_storage uaddr_buf;
	struct pt_regs *user_regs = NULL;
	uid_t uid = from_kuid(&init_user_ns, current_uid());
	struct vpnhide_port_targets *t;
	struct vpnhide_uid_port_rules *urules = NULL;
	int fd = -1;
	bool put_needed = false;
	int i;

	if (!is_hook_active(HOOK_BIND))
		return 1;

	sock = resolve_sock_addr(regs, sys_bind_uses_wrapper,
				 (struct sockaddr *)&uaddr_buf,
				 sizeof(uaddr_buf), &addr, &put_needed, &fd);

	if (sys_bind_uses_wrapper) {
		user_regs = (struct pt_regs *)regs->regs[0];
		if (!sock)
			return 0;
	}

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
		if (put_needed)
			sockfd_put(sock);
		return 1;
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

			if (should_block_port(urules, port, proto)) {
				if (sys_bind_uses_wrapper && user_regs) {
					unsigned short zero_port = 0;
					void __user *uaddr_ptr =
						(void __user *)
							user_regs->regs[1];
					if (copy_to_user(
						    uaddr_ptr +
							    offsetof(
								    struct sockaddr_in,
								    sin_port),
						    &zero_port,
						    sizeof(zero_port))) {
						vpnhide_dbg(
							"socket_bind: copy_to_user failed for IPv4 uid=%u\n",
							uid);
					}
				} else {
					sin->sin_port = 0;
				}
				vpnhide_dbg(
					"socket_bind: redirected IPv4 port %u to 0 for uid=%u\n",
					port, uid);
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

			if (should_block_port(urules, port, proto)) {
				if (sys_bind_uses_wrapper && user_regs) {
					unsigned short zero_port = 0;
					void __user *uaddr_ptr =
						(void __user *)
							user_regs->regs[1];
					if (copy_to_user(
						    uaddr_ptr +
							    offsetof(
								    struct sockaddr_in6,
								    sin6_port),
						    &zero_port,
						    sizeof(zero_port))) {
						vpnhide_dbg(
							"socket_bind: copy_to_user failed for IPv6 uid=%u\n",
							uid);
					}
				} else {
					sin6->sin6_port = 0;
				}
				vpnhide_dbg(
					"socket_bind: redirected IPv6 port %u to 0 for uid=%u\n",
					port, uid);
			}
		}
	}
	rcu_read_unlock();

	if (put_needed)
		sockfd_put(sock);

	return 1;
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
	.kp.symbol_name = "__arm64_sys_bind",
};

/* ================================================================== */
/*  Hook 12d: inet6_bind — IPv6 link-local scope_id probe suppression */
/*  Android source path: net/ipv6/af_inet6.c                          */
/*                                                                    */
/*  What it does:                                                     */
/*    Intercepts inet6_bind() for target UIDs. When the app calls     */
/*    bind(AF_INET6, {fe80::, scope_id=i}), the kernel validates i    */
/*    against the interface table and returns any code except ENODEV  */
/*    if the interface exists — enough to confirm its presence.       */
/*    This hook returns -ENODEV when scope_id matches a hidden VPN    */
/*    interface index, making the interface invisible to blind         */
/*    index bruteforce probes.                                        */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Iterating scope_id 1..64 and binding fe80::1 reveals which      */
/*    indices have active interfaces without any enumeration API.     */
/*    check_ipv6_link_local_bruteforce Pass 1 uses this technique.    */
/*                                                                    */
/*  inet6_bind(struct socket *sock, struct sockaddr *uaddr, int len)  */
/*  arm64: x1 = uaddr (kernel ptr, already copied by move_addr_to_   */
/*         kernel in __sys_bind before sock->ops->bind is invoked).   */
/* ================================================================== */

struct inet6_bind_ll_data {
	bool should_deny;
};

static int inet6_bind_ll_entry(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	struct inet6_bind_ll_data *data;
	struct sockaddr_in6 sin6;

	if (!is_hook_active(HOOK_INET6_BIND_LL))
		return 1;

	if (!is_target_uid())
		return 1;

	/*
	 * uaddr (x1) is a kernel pointer — __sys_bind calls
	 * move_addr_to_kernel() before invoking sock->ops->bind, so by
	 * the time inet6_bind runs the address lives on the kernel stack.
	 * Use copy_from_kernel_nofault for safe access without PAN faults.
	 */
	if (copy_from_kernel_nofault(&sin6, (const void *)regs->regs[1],
				     sizeof(sin6)) != 0)
		return 1;

	if (sin6.sin6_family != AF_INET6)
		return 1;

	/* fe80::/10 link-local prefix: first byte 0xfe, second byte 0x80–0xbf */
	if (sin6.sin6_addr.s6_addr[0] != 0xfe ||
	    (sin6.sin6_addr.s6_addr[1] & 0xc0) != 0x80)
		return 1;

	if (sin6.sin6_scope_id == 0 ||
	    !is_active_vpn_ifindex(sin6.sin6_scope_id))
		return 1;

	data = (void *)ri->data;
	data->should_deny = true;

	vpnhide_dbg(
		"inet6_bind_ll: suppressing link-local probe scope_id=%u uid=%u\n",
		sin6.sin6_scope_id, from_kuid(&init_user_ns, current_uid()));
	return 0;
}

static int inet6_bind_ll_ret(struct kretprobe_instance *ri,
			     struct pt_regs *regs)
{
	struct inet6_bind_ll_data *data = (void *)ri->data;

	if (!data->should_deny)
		return 0;

	record_kmod_intercept(from_kuid(&init_user_ns, current_uid()), 4);
	regs_set_return_value(regs, -ENODEV);
	return 0;
}

static struct kretprobe inet6_bind_ll_krp = {
	.handler = inet6_bind_ll_ret,
	.entry_handler = inet6_bind_ll_entry,
	.data_size = sizeof(struct inet6_bind_ll_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "inet6_bind",
};

/* ================================================================== */
/*  Hook 12e: udpv6_sendmsg — block IPv6 link-local sendto on VPN     */
/*  Suppresses Pass 3 (NDP timeout oracle) and Pass 4 (qdisc flood)   */
/*  of check_ipv6_link_local_bruteforce in fallback mode.             */
/*  Pass 3: send_ret < 0 (-ENOBUFS) → check skips the index,         */
/*          no 3.5 s NDP wait, no errqueue inspection.                */
/*  Pass 4: ENOBUFS → flood loop breaks on first iteration,           */
/*          successful_sends stays 0 which is ≤ 5000 → not flagged.  */
/* ================================================================== */

struct udpv6_sendmsg_ll_data {
	bool should_deny;
};

static int udpv6_sendmsg_ll_entry(struct kretprobe_instance *ri,
				  struct pt_regs *regs)
{
	struct udpv6_sendmsg_ll_data *data;
	struct msghdr *msg;
	struct sockaddr_in6 sin6;

	if (!is_hook_active(HOOK_UDPV6_SENDMSG))
		return 1;

	if (!is_target_uid())
		return 1;

	/*
	 * udpv6_sendmsg(struct sock *sk, struct msghdr *msg, size_t len)
	 * x1 = msg.  msg lives on the kernel stack of __sys_sendto;
	 * msg->msg_name points at the sockaddr_storage copy that
	 * move_addr_to_kernel() filled before reaching the transport layer.
	 */
	msg = (struct msghdr *)regs->regs[1];
	if (!msg || !msg->msg_name)
		return 1;

	if (msg->msg_namelen < (int)sizeof(sin6))
		return 1;

	if (copy_from_kernel_nofault(&sin6, msg->msg_name, sizeof(sin6)) != 0)
		return 1;

	if (sin6.sin6_family != AF_INET6)
		return 1;

	/* fe80::/10 link-local */
	if (sin6.sin6_addr.s6_addr[0] != 0xfe ||
	    (sin6.sin6_addr.s6_addr[1] & 0xc0) != 0x80)
		return 1;

	if (sin6.sin6_scope_id == 0 ||
	    !is_active_vpn_ifindex(sin6.sin6_scope_id))
		return 1;

	data = (void *)ri->data;
	data->should_deny = true;

	vpnhide_dbg("udpv6_sendmsg_ll: blocking ll sendto scope_id=%u uid=%u\n",
		    sin6.sin6_scope_id,
		    from_kuid(&init_user_ns, current_uid()));
	return 0;
}

static int udpv6_sendmsg_ll_ret(struct kretprobe_instance *ri,
				struct pt_regs *regs)
{
	struct udpv6_sendmsg_ll_data *data = (void *)ri->data;

	if (!data->should_deny)
		return 0;

	record_kmod_intercept(from_kuid(&init_user_ns, current_uid()), 4);
	regs_set_return_value(regs, -ENOBUFS);
	return 0;
}

static struct kretprobe udpv6_sendmsg_ll_krp = {
	.handler = udpv6_sendmsg_ll_ret,
	.entry_handler = udpv6_sendmsg_ll_entry,
	.data_size = sizeof(struct udpv6_sendmsg_ll_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "udpv6_sendmsg",
};

/* ================================================================== */
/*  Hook 13: inet_getname & inet6_getname — getsockname Spoofing      */
/*  Android source path:                                              */
/*    - inet_getname: net/ipv4/af_inet.c                              */
/*    - inet6_getname: net/ipv6/af_inet6.c                            */
/*                                                                    */
/*  What it does:                                                     */
/*    Intercepts getsockname() calls for target UIDs. If the local    */
/*    socket address returned corresponds to the VPN interface's      */
/*    private IP, it rewrites the IP in the response structure to a   */
/*    standard non-VPN address (e.g. Wi-Fi/cellular IP) to spoof it.  */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    When target apps call getsockname(), they receive the exact     */
/*    private IP address of the VPN interface, exposing the tunnel.   */
/* ================================================================== */

static bool sys_getsockname_uses_wrapper;

struct sys_getsockname_data {
	void __user *uaddr;
};

static int sys_getsockname_entry(struct kretprobe_instance *ri,
				 struct pt_regs *regs)
{
	struct sys_getsockname_data *data;
	struct pt_regs *user_regs;

	if (!is_hook_active(HOOK_GETNAME_INET) &&
	    !is_hook_active(HOOK_GETNAME_INET6))
		return 1;

	if (!is_target_uid())
		return 1;

	user_regs = (struct pt_regs *)regs->regs[0];
	if (!user_regs || (unsigned long)user_regs < 0xFFFF000000000000ULL)
		return 1;

	data = (void *)ri->data;
	data->uaddr = (void __user *)user_regs->regs[1];
	return 0;
}

static void spoof_getsockname_ipv4(void __user *uaddr,
				   struct vpnhide_spoof_ip *sip)
{
	__be32 addr;
	__be32 target_ip;

	if (get_user(addr,
		     &((struct sockaddr_in __user *)uaddr)->sin_addr.s_addr) !=
	    0)
		return;

	if (addr == 0 || (ntohl(addr) & 0xFF000000) == 0x7F000000)
		return;

	target_ip = sip->has_ipv4 ? sip->ipv4_addr : htonl(0xC0000004);
	if (put_user(target_ip,
		     &((struct sockaddr_in __user *)uaddr)->sin_addr.s_addr) ==
	    0) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      5);
		vpnhide_dbg(
			"sys_getsockname_ret: spoofed IPv4 from %pI4 to %pI4\n",
			&addr, &target_ip);
	}
}

static void spoof_getsockname_ipv6(void __user *uaddr,
				   struct vpnhide_spoof_ip *sip)
{
	struct in6_addr addr6;
	struct in6_addr old_addr;
	struct in6_addr target_ip6;

	if (copy_from_user(&addr6,
			   &((struct sockaddr_in6 __user *)uaddr)->sin6_addr,
			   sizeof(struct in6_addr)) != 0)
		return;

	if (ipv6_addr_any(&addr6) || ipv6_addr_loopback(&addr6))
		return;

	old_addr = addr6;

	if (sip->has_ipv6) {
		memcpy(&target_ip6, sip->ipv6_addr, 16);
	} else {
		memset(&target_ip6, 0, 16);
		target_ip6.s6_addr[0] = 0x20;
		target_ip6.s6_addr[1] = 0x01;
		target_ip6.s6_addr[2] = 0x0d;
		target_ip6.s6_addr[3] = 0xb8;
		target_ip6.s6_addr[15] = 0x10;
	}

	if (copy_to_user(&((struct sockaddr_in6 __user *)uaddr)->sin6_addr,
			 &target_ip6, sizeof(struct in6_addr)) == 0) {
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      5);
		vpnhide_dbg(
			"sys_getsockname_ret: spoofed IPv6 from %pI6c to %pI6c\n",
			&old_addr, &target_ip6);
	}
}

static int sys_getsockname_ret(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	struct sys_getsockname_data *data = (void *)ri->data;
	int retval = regs_return_value(regs);
	unsigned short sa_family;
	struct vpnhide_spoof_ip sip;

	if (retval != 0 || !data->uaddr)
		return 0;

	if (get_user(sa_family, (unsigned short __user *)data->uaddr) != 0)
		return 0;

	get_spoof_ip(&sip);

	if (sa_family == AF_INET) {
		spoof_getsockname_ipv4(data->uaddr, &sip);
	} else if (sa_family == AF_INET6) {
		spoof_getsockname_ipv6(data->uaddr, &sip);
	}

	return 0;
}

static struct kretprobe sys_getsockname_krp = {
	.handler = sys_getsockname_ret,
	.entry_handler = sys_getsockname_entry,
	.data_size = sizeof(struct sys_getsockname_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_getsockname",
};

struct getname_data {
	struct sockaddr *uaddr;
	int peer;
};

static int inet_getname_entry(struct kretprobe_instance *ri,
			      struct pt_regs *regs)
{
	struct getname_data *data;
	int peer = (int)regs->regs[2];

	if (!is_hook_active(HOOK_GETNAME_INET))
		return 1;

	if (peer != 0 || !is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->uaddr = (struct sockaddr *)regs->regs[1];
	data->peer = peer;
	return 0;
}

static int inet_getname_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct getname_data *data = (void *)ri->data;
	int retval = regs_return_value(regs);

	if (retval >= 0 && data->uaddr) {
		struct sockaddr_in *sin = (struct sockaddr_in *)data->uaddr;
		struct vpnhide_spoof_ip sip;
		get_spoof_ip(&sip);

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

	if (!is_hook_active(HOOK_GETNAME_INET6))
		return 1;

	if (peer != 0 || !is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->uaddr = (struct sockaddr *)regs->regs[1];
	data->peer = peer;
	return 0;
}

static int inet6_getname_ret(struct kretprobe_instance *ri,
			     struct pt_regs *regs)
{
	struct getname_data *data = (void *)ri->data;
	int retval = regs_return_value(regs);

	if (retval >= 0 && data->uaddr) {
		struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)data->uaddr;
		struct vpnhide_spoof_ip sip;
		get_spoof_ip(&sip);

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
						      5);
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

	if (!is_hook_active(HOOK_SOCK_IOCTL))
		return 1;

	if (cmd != SIOCGIFCONF)
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->argp = (void __user *)arg;
	vpnhide_dbg("sock_ioctl_entry: uid=%u SIOCGIFCONF argp=%px\n",
		    from_kuid(&init_user_ns, current_uid()), data->argp);
	return 0;
}

/* ================================================================== */
/*  eBPF Map Hijacking / Stats Hiding                                 */
/*                                                                    */
/*  What it does:                                                     */
/*    Intercepts __arm64_sys_bpf syscalls to modify BPF map lookups.  */
/*    When netd/tethering traffic stats maps (like iface_stats or     */
/*    stats_map_) are queried, it zeroes out records mapped to the    */
/*    VPN interface or target UIDs, and updates the cover interface   */
/*    to absorb the VPN's traffic to avoid overall traffic gaps.      */
/*                                                                    */
/*  Consequence of absence (What happens without this hook):          */
/*    Target apps checking network stats via modern Android API       */
/*    NetworkStatsManager (which queries kernel eBPF maps directly)    */
/*    will see the VPN interface index and its data counters, leaking */
/*    the active VPN interface and its traffic volume.                */
/* ================================================================== */

static inline bool is_stats_or_uid_map(const char *name)
{
	if (!name || name[0] == '\0')
		return false;

	switch (name[0]) {
	case 'a':
		return strncmp(name, "app_uid_stats", 13) == 0;
	case 's':
		return strncmp(name, "stats_map_", 10) == 0;
	case 'i':
		return strncmp(name, "iface_stats", 11) == 0;
	case 'u':
		return strncmp(name, "uid_stats", 9) == 0;
	case 't':
		return strncmp(name, "tether_stats", 12) == 0;
	case 'm':
		return (strncmp(name, "map_netd_app_ui", 15) == 0 ||
			strncmp(name, "map_netd_stats", 14) == 0 ||
			strncmp(name, "map_netd_iface_", 15) == 0 ||
			strncmp(name, "map_netd_uid_st", 15) == 0);
	default:
		return false;
	}
}

static bool is_key_vpn_or_target_uid(struct bpf_map *map, void *key)
{
	if (!map || !key)
		return false;

	if (strncmp(map->name, "stats_map_", 10) == 0 ||
	    strncmp(map->name, "map_netd_stats", 14) == 0) {
		struct vh_stats_key *sk = (struct vh_stats_key *)key;

		vpnhide_dbg("key_check stats_map '%s': uid=%u index=%u\n",
			    map->name, sk->uid, sk->ifaceIndex);

		if (is_active_vpn_ifindex(sk->ifaceIndex) ||
		    is_target_uid_val(sk->uid)) {
			vpnhide_dbg(
				"BPF Match stats_map '%s': uid=%u index=%u -> SPOOFING ZERO STATS\n",
				map->name, sk->uid, sk->ifaceIndex);
			return true;
		}
	} else if (strncmp(map->name, "iface_stats", 11) == 0 ||
		   strncmp(map->name, "map_netd_iface_", 15) == 0 ||
		   strncmp(map->name, "tether_stats", 12) == 0) {
		u32 ifaceIndex = *(u32 *)key;

		vpnhide_dbg("key_check iface/tether '%s': index=%u\n",
			    map->name, ifaceIndex);

		if (is_active_vpn_ifindex(ifaceIndex)) {
			vpnhide_dbg(
				"BPF Match iface/tether stats '%s': index=%u -> SPOOFING ZERO STATS\n",
				map->name, ifaceIndex);
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

static bool sys_bpf_uses_wrapper;
static struct kretprobe sys_bpf_krp;

static int sys_bpf_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct sys_bpf_data *data = (struct sys_bpf_data *)ri->data;
	int cmd;
	union bpf_attr __user *uattr;
	unsigned int size;

	if (!is_hook_active(HOOK_BPF) || is_target_uid()) {
		data->uattr = NULL;
		return 1;
	}

	if (sys_bpf_uses_wrapper) {
		struct pt_regs *user_regs = (struct pt_regs *)regs->regs[0];
		if (user_regs &&
		    (unsigned long)user_regs >= 0xFFFF000000000000ULL) {
			cmd = (int)user_regs->regs[0];
			uattr = (union bpf_attr __user *)user_regs->regs[1];
			size = (unsigned int)user_regs->regs[2];
		} else {
			return 1;
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

static struct bpf_map *bpf_map_from_fd(u32 map_fd, struct fd *out_f)
{
	struct file *file_ptr;
	unsigned long magic = 0;
	const char *dname = "unknown";

	*out_f = fdget(map_fd);
	file_ptr = vh_fd_file(*out_f);
	if (!file_ptr)
		goto fail;

	if (file_ptr->f_path.dentry) {
		dname = file_ptr->f_path.dentry->d_name.name;
		if (file_ptr->f_path.dentry->d_sb) {
			magic = file_ptr->f_path.dentry->d_sb->s_magic;
		}
	}

	if (file_ptr->private_data) {
		bool is_bpf_file = false;

		if (magic == BPF_FS_MAGIC) {
			is_bpf_file = true;
		} else if ((magic == 0x09041934 || magic == 0x09041957) &&
			   dname && strcmp(dname, "bpf-map") == 0) {
			is_bpf_file = true;
		}

		if (is_bpf_file) {
			struct bpf_map *map = file_ptr->private_data;

			if (map && !IS_ERR(map)) {
				return map;
			}
		}
	}

fail:
	fdput(*out_f);
	return NULL;
}

static void collect_vpn_traffic_sum(struct bpf_map *map,
				    struct vh_stats_value *vpn_sum)
{
	struct vpnhide_active_vpns *vpns;
	rcu_read_lock();
	vpns = rcu_dereference(global_active_vpns);
	if (vpns) {
		int idx;
		for (idx = 0; idx < vpns->count; idx++) {
			u32 vpn_idx = vpns->vpns[idx].ifindex;
			void *map_val =
				map->ops->map_lookup_elem(map, &vpn_idx);
			if (map_val) {
				struct vh_stats_value *sv =
					(struct vh_stats_value *)map_val;
				sv_add(vpn_sum, sv);
			}
		}
	}
	rcu_read_unlock();
}

static void bpf_single_cover_update(struct bpf_map *map, void __user *usr_val,
				    u32 value_size, void *vbuf,
				    struct vh_stats_value *vpn_sum)
{
	struct vh_stats_value *sv;

	if (!sv_rx_bytes(vpn_sum) && !sv_tx_bytes(vpn_sum))
		return;

	if (copy_from_user(vbuf, usr_val, value_size) != 0)
		return;

	sv = (struct vh_stats_value *)vbuf;
	sv_add(sv, vpn_sum);

	if (copy_to_user(usr_val, vbuf, value_size) != 0) {
		vpnhide_dbg(
			"sys_bpf_ret: single cover update copy_to_user failed\n");
	} else {
		vpnhide_dbg(
			"sys_bpf_ret: single cover update for map '%s' success (rx=%llu, tx=%llu)\n",
			map->name, sv_rx_bytes(vpn_sum), sv_tx_bytes(vpn_sum));
	}
}

static void bpf_single_lookup_zero(struct bpf_map *map,
				   const union bpf_attr *attr, u32 key_size,
				   u32 value_size)
{
	u8 kbuf_stack[64];
	u8 vbuf_stack[256];
	void *kbuf = NULL;
	void *vbuf = NULL;
	void __user *usr_key;
	void __user *usr_val;
	u32 ifaceIndex;
	u32 cover_idx;
	struct vh_stats_value vpn_sum;

	if (key_size <= sizeof(kbuf_stack)) {
		kbuf = kbuf_stack;
	} else {
		kbuf = kmalloc(key_size, GFP_KERNEL);
	}

	if (value_size <= sizeof(vbuf_stack)) {
		vbuf = vbuf_stack;
		memset(vbuf, 0, value_size);
	} else {
		vbuf = kzalloc(value_size, GFP_KERNEL);
	}

	if (!kbuf || !vbuf)
		goto out;

	usr_key = (void __user *)(unsigned long)attr->key;
	usr_val = (void __user *)(unsigned long)attr->value;

	if (copy_from_user(kbuf, usr_key, key_size) != 0)
		goto out;

	if (is_key_vpn_or_target_uid(map, kbuf)) {
		vpnhide_dbg("sys_bpf_ret: single zeroing for map '%s'\n",
			    map->name);
		if (copy_to_user(usr_val, vbuf, value_size)) {
			vpnhide_dbg(
				"sys_bpf_ret: single zeroing copy_to_user failed\n");
		}
	} else if (strncmp(map->name, "iface_stats", 11) == 0 ||
		   strncmp(map->name, "map_netd_iface_", 15) == 0 ||
		   strncmp(map->name, "tether_stats", 12) == 0) {
		ifaceIndex = *(u32 *)kbuf;
		cover_idx = (u32)atomic_read(&global_cover_ifindex);
		if (cover_idx && ifaceIndex == cover_idx) {
			memset(&vpn_sum, 0, sizeof(vpn_sum));
			collect_vpn_traffic_sum(map, &vpn_sum);
			bpf_single_cover_update(map, usr_val, value_size, vbuf,
						&vpn_sum);
		}
	}

out:
	if (kbuf && kbuf != kbuf_stack)
		kfree(kbuf);
	if (vbuf && vbuf != vbuf_stack)
		kfree(vbuf);
}

static void bpf_batch_zero_iface(struct bpf_map *map, void __user *usr_keys,
				 void __user *usr_vals, u32 count, u32 key_size,
				 u32 value_size, void *kbuf, void *vbuf)
{
	struct vh_stats_value vpn_sum = { 0 };
	u32 cover_idx = (u32)atomic_read(&global_cover_ifindex);
	u32 cover_pos = UINT_MAX;
	u32 i;

	for (i = 0; i < count; i++) {
		u32 ifindex;

		if (copy_from_user(kbuf, (char __user *)usr_keys + i * key_size,
				   key_size))
			continue;
		ifindex = *(u32 *)kbuf;

		if (is_active_vpn_ifindex(ifindex)) {
			if (copy_from_user(vbuf,
					   (char __user *)usr_vals +
						   i * value_size,
					   value_size) == 0) {
				struct vh_stats_value *sv =
					(struct vh_stats_value *)vbuf;
				sv_add(&vpn_sum, sv);
			}
			memset(vbuf, 0, value_size);
			if (copy_to_user((char __user *)usr_vals +
						 i * value_size,
					 vbuf, value_size)) {
				vpnhide_dbg(
					"sys_bpf_ret: batch zeroing copy_to_user failed\n");
			}
		} else if (cover_idx && ifindex == cover_idx) {
			cover_pos = i;
		}
	}

	if (cover_pos != UINT_MAX &&
	    (sv_rx_bytes(&vpn_sum) || sv_tx_bytes(&vpn_sum))) {
		if (copy_from_user(vbuf,
				   (char __user *)usr_vals +
					   cover_pos * value_size,
				   value_size) == 0) {
			struct vh_stats_value *sv =
				(struct vh_stats_value *)vbuf;
			sv_add(sv, &vpn_sum);
			if (copy_to_user((char __user *)usr_vals +
						 cover_pos * value_size,
					 vbuf, value_size)) {
				vpnhide_dbg(
					"sys_bpf_ret: batch cover update copy_to_user failed\n");
			}
		}
	}
}

static void bpf_batch_zero_generic(struct bpf_map *map, void __user *usr_keys,
				   void __user *usr_vals, u32 count,
				   u32 key_size, u32 value_size, void *kbuf,
				   void *vbuf)
{
	u32 i;

	for (i = 0; i < count; i++) {
		if (copy_from_user(kbuf, (char __user *)usr_keys + i * key_size,
				   key_size) == 0) {
			if (is_key_vpn_or_target_uid(map, kbuf)) {
				memset(vbuf, 0, value_size);
				if (copy_to_user((char __user *)usr_vals +
							 i * value_size,
						 vbuf, value_size)) {
					vpnhide_dbg(
						"sys_bpf_ret: batch zeroing copy_to_user failed\n");
				}
			}
		}
	}
}

static void bpf_batch_lookup_zero(struct bpf_map *map,
				  const struct sys_bpf_data *data, u32 key_size,
				  u32 value_size)
{
	u32 count = 0;

	if (get_user(count, &data->uattr->batch.count) == 0 && count > 0) {
		void __user *usr_keys =
			(void __user *)(unsigned long)data->attr.batch.keys;
		void __user *usr_vals =
			(void __user *)(unsigned long)data->attr.batch.values;

		if (usr_keys && usr_vals) {
			u8 kbuf_stack[64];
			u8 vbuf_stack[256];
			void *kbuf = NULL;
			void *vbuf = NULL;

			if (key_size <= sizeof(kbuf_stack)) {
				kbuf = kbuf_stack;
			} else {
				kbuf = kmalloc(key_size, GFP_KERNEL);
			}

			if (value_size <= sizeof(vbuf_stack)) {
				vbuf = vbuf_stack;
			} else {
				vbuf = kmalloc(value_size, GFP_KERNEL);
			}

			if (kbuf && vbuf) {
				if (strncmp(map->name, "iface_stats", 11) ==
					    0 ||
				    strncmp(map->name, "map_netd_iface_stats",
					    20) == 0) {
					bpf_batch_zero_iface(map, usr_keys,
							     usr_vals, count,
							     key_size,
							     value_size, kbuf,
							     vbuf);
				} else {
					bpf_batch_zero_generic(map, usr_keys,
							       usr_vals, count,
							       key_size,
							       value_size, kbuf,
							       vbuf);
				}
			}
			if (kbuf && kbuf != kbuf_stack)
				kfree(kbuf);
			if (vbuf && vbuf != vbuf_stack)
				kfree(vbuf);
		}
	}
}

static int sys_bpf_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct sys_bpf_data *data = (struct sys_bpf_data *)ri->data;
	int ret_val = regs_return_value(regs);
	struct bpf_map *map;
	struct fd f;

	if (!data || !data->uattr)
		return 0;

	/* BATCH lookups can return -ENOENT but still populate some keys/values */
	if (ret_val < 0 && ret_val != -ENOENT)
		return 0;

	if (data->cmd != BPF_MAP_LOOKUP_ELEM &&
	    data->cmd != BPF_MAP_LOOKUP_AND_DELETE_ELEM &&
	    data->cmd != BPF_MAP_LOOKUP_BATCH &&
	    data->cmd != BPF_MAP_LOOKUP_AND_DELETE_BATCH) {
		return 0;
	}

	map = bpf_map_from_fd(data->map_fd, &f);
	if (!map)
		return 0;

	if (is_stats_or_uid_map(map->name)) {
		u32 key_size = map->key_size;
		u32 value_size = map->value_size;

		vpnhide_dbg("sys_bpf_ret: matched map '%s', cmd=%d\n",
			    map->name, data->cmd);

		/* Handle single lookup */
		if ((data->cmd == BPF_MAP_LOOKUP_ELEM ||
		     data->cmd == BPF_MAP_LOOKUP_AND_DELETE_ELEM) &&
		    ret_val == 0) {
			bpf_single_lookup_zero(map, &data->attr, key_size,
					       value_size);
		}
		/* Handle batch lookup */
		else if (data->cmd == BPF_MAP_LOOKUP_BATCH ||
			 data->cmd == BPF_MAP_LOOKUP_AND_DELETE_BATCH) {
			bpf_batch_lookup_zero(map, data, key_size, value_size);
		}
	}

	fdput(f);
	return 0;
}

static struct kretprobe sys_bpf_krp = {
	.entry_handler = sys_bpf_entry,
	.handler = sys_bpf_ret,
	.data_size = sizeof(struct sys_bpf_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_bpf",
};

static const char *const vh_guarded_dir_prefixes[] = {
	"/proc/sys/net/ipv4/conf",  "/proc/sys/net/ipv6/conf",
	"/proc/sys/net/ipv4/neigh", "/proc/sys/net/ipv6/neigh",
	"/proc/net/dev_snmp6",	    "/sys/class/net",
};

static bool vh_is_path_guarded(struct file *file, char *buf, int buflen)
{
	char *path_ptr;
	int i;

	if (!file)
		return false;

	path_ptr = d_path(&file->f_path, buf, buflen);
	if (IS_ERR(path_ptr))
		return false;

	for (i = 0; i < ARRAY_SIZE(vh_guarded_dir_prefixes); i++) {
		size_t len = strlen(vh_guarded_dir_prefixes[i]);
		if (strncmp(path_ptr, vh_guarded_dir_prefixes[i], len) == 0) {
			if (path_ptr[len] == '\0' || path_ptr[len] == '/')
				return true;
		}
	}

	return false;
}

struct vh_linux_dirent64 {
	u64 d_ino;
	s64 d_off;
	unsigned short d_reclen;
	unsigned char d_type;
	char d_name[];
};

struct getdents64_data {
	void __user *dirp;
	unsigned int count;
	bool should_filter;
};

static bool sys_getdents64_uses_wrapper = false;

static int sys_getdents64_entry(struct kretprobe_instance *ri,
				struct pt_regs *regs)
{
	struct getdents64_data *data;
	int fd;
	void __user *dirp;
	unsigned int count;
	struct fd f;
	struct file *file_ptr;
	char path_buf[256];
	bool is_guarded = false;

	if (!is_hook_active(HOOK_GETDENTS64))
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->should_filter = false;

	if (sys_getdents64_uses_wrapper) {
		struct pt_regs *user_regs = (struct pt_regs *)regs->regs[0];
		if (user_regs &&
		    (unsigned long)user_regs >= 0xFFFF000000000000ULL) {
			fd = (int)user_regs->regs[0];
			dirp = (void __user *)user_regs->regs[1];
			count = (unsigned int)user_regs->regs[2];
		} else {
			return 1;
		}
	} else {
		fd = (int)regs->regs[0];
		dirp = (void __user *)regs->regs[1];
		count = (unsigned int)regs->regs[2];
	}

	data->dirp = dirp;
	data->count = count;

	f = fdget(fd);
	file_ptr = vh_fd_file(f);
	if (file_ptr) {
		is_guarded = vh_is_path_guarded(file_ptr, path_buf,
						sizeof(path_buf));
	}
	fdput(f);

	if (is_guarded) {
		data->should_filter = true;
		vpnhide_dbg("getdents64_entry: guarding fd=%d\n", fd);
		return 0;
	}

	return 1;
}

static int sys_getdents64_ret(struct kretprobe_instance *ri,
			      struct pt_regs *regs)
{
	struct getdents64_data *data = (void *)ri->data;
	int retval = (int)regs_return_value(regs);
	u8 *buf = NULL;

	if (!data->should_filter || retval <= 0)
		return 0;

	buf = kvmalloc(retval, GFP_ATOMIC);
	if (!buf)
		return 0;

	if (copy_from_user(buf, data->dirp, retval) == 0) {
		u8 *p = buf;
		u8 *end = buf + retval;
		u8 *dst = buf;
		int modified = 0;

		while (p < end) {
			struct vh_linux_dirent64 *de =
				(struct vh_linux_dirent64 *)p;
			if (de->d_reclen < sizeof(struct vh_linux_dirent64) ||
			    p + de->d_reclen > end)
				break;

			if (vh_is_vpn_name_cached(de->d_name,
						  strlen(de->d_name))) {
				vpnhide_dbg(
					"sys_getdents64_ret: filtering out entry '%s'\n",
					de->d_name);
				record_kmod_intercept(from_kuid(&init_user_ns,
								current_uid()),
						      2);
				modified = 1;
			} else {
				if (dst != p) {
					memmove(dst, p, de->d_reclen);
				}
				dst += de->d_reclen;
			}
			p += de->d_reclen;
		}

		if (modified) {
			int new_len = dst - buf;
			if (copy_to_user(data->dirp, buf, new_len) == 0) {
				regs_set_return_value(regs, new_len);
			}
		}
	}

	kvfree(buf);
	return 0;
}

static struct kretprobe sys_getdents64_krp = {
	.entry_handler = sys_getdents64_entry,
	.handler = sys_getdents64_ret,
	.data_size = sizeof(struct getdents64_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_getdents64",
};

static int dev_seq_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data;
	if (!is_hook_active(HOOK_DEV_SEQ))
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->seq = (struct seq_file *)regs->regs[0];
	data->start_count = data->seq ? data->seq->count : 0;

	vpnhide_dbg("dev_seq_entry: uid=%u target=1\n",
		    from_kuid(&init_user_ns, current_uid()));

	return 0;
}

static int dev_seq_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data = (void *)ri->data;
	struct seq_file *seq = data->seq;
	char *buf, *src, *dst, *end;
	char ifname[IFNAMSIZ];
	int j;

	if (!seq || !seq->buf)
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
		char *p = src;

		while (p < line_end && (*p == ' ' || *p == '\t'))
			p++;

		j = 0;
		while (p < line_end && *p != ':' && *p != ' ' && *p != '\t' &&
		       *p != '\n' && j < IFNAMSIZ - 1) {
			ifname[j++] = *p++;
		}
		ifname[j] = '\0';

		if (j > 0 && is_vpn_ifname(ifname)) {
			vpnhide_dbg("dev_seq_ret: hiding statistics for %s\n",
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

static struct kretprobe dev_seq_krp = {
	.handler = dev_seq_ret,
	.entry_handler = dev_seq_entry,
	.data_size = sizeof(struct fib_route_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "dev_seq_show",
};

static int if6_seq_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data;
	if (!is_hook_active(HOOK_IF6_SEQ))
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->seq = (struct seq_file *)regs->regs[0];
	data->start_count = data->seq ? data->seq->count : 0;

	vpnhide_dbg("if6_seq_entry: uid=%u target=1\n",
		    from_kuid(&init_user_ns, current_uid()));

	return 0;
}

static int if6_seq_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data = (void *)ri->data;
	struct seq_file *seq = data->seq;
	char *buf, *src, *dst, *end;
	char ifname[IFNAMSIZ];
	int j;

	if (!seq || !seq->buf)
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
			vpnhide_dbg("if6_seq_ret: hiding interface %s\n",
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

static struct kretprobe if6_seq_krp = {
	.handler = if6_seq_ret,
	.entry_handler = if6_seq_entry,
	.data_size = sizeof(struct fib_route_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "if6_seq_show",
};

static int get_path_from_dfd_and_name(int dfd, const char __user *filename,
				      char *buf, int buflen)
{
	char name[256];
	struct fd f;
	struct file *file_ptr;
	char path_buf[256];
	char *path_ptr;
	int len;
	int name_len;

	if (!filename)
		return -EINVAL;

	name_len = strncpy_from_user(name, filename, sizeof(name) - 1);
	if (name_len < 0)
		return name_len;
	name[name_len] = '\0';

	if (name[0] == '/') {
		len = snprintf(buf, buflen, "%s", name);
		return (len >= buflen) ? -ENAMETOOLONG : 0;
	}

	if (dfd == AT_FDCWD) {
		/* Resolving AT_FDCWD (cwd of process) is possible via pwd path,
		 * but in Android apps, relative lookups to /sys or /proc are extremely rare.
		 * Usually they do absolute path access like access("/sys/class/net/tun0", F_OK).
		 * Thus, we check if the filename contains "tun", "wg", "ppp", etc. and matches.
		 * To be absolutely robust, we can copy name to buf. */
		len = snprintf(buf, buflen, "%s", name);
		return (len >= buflen) ? -ENAMETOOLONG : 0;
	}

	f = fdget(dfd);
	file_ptr = vh_fd_file(f);
	if (!file_ptr) {
		fdput(f);
		return -EBADF;
	}

	path_ptr = d_path(&file_ptr->f_path, path_buf, sizeof(path_buf));
	if (IS_ERR(path_ptr)) {
		fdput(f);
		return PTR_ERR(path_ptr);
	}

	len = snprintf(buf, buflen, "%s/%s", path_ptr, name);
	fdput(f);

	return (len >= buflen) ? -ENAMETOOLONG : 0;
}

static bool vh_is_resolved_path_guarded_vpn(const char *path)
{
	int i;
	const char *last_slash;
	const char *iface_name;
	size_t iface_len;

	if (!path)
		return false;

	for (i = 0; i < ARRAY_SIZE(vh_guarded_dir_prefixes); i++) {
		size_t prefix_len = strlen(vh_guarded_dir_prefixes[i]);
		if (strncmp(path, vh_guarded_dir_prefixes[i], prefix_len) ==
		    0) {
			if (path[prefix_len] == '/') {
				last_slash = strrchr(path, '/');
				if (last_slash) {
					iface_name = last_slash + 1;
					iface_len = strlen(iface_name);
					if (vh_is_vpn_name_cached(iface_name,
								  iface_len)) {
						return true;
					}
				}
			}
		}
	}
	return false;
}

static bool sys_openat_uses_wrapper = false;
static bool sys_openat2_uses_wrapper = false;
static bool sys_faccessat_uses_wrapper = false;
static bool sys_faccessat2_uses_wrapper = false;
static bool sys_newfstatat_uses_wrapper = false;
static bool sys_readlinkat_uses_wrapper = false;

struct path_oracle_data {
	bool should_deny;
};

static int path_oracle_entry(struct kretprobe_instance *ri,
			     struct pt_regs *regs, int dfd_idx,
			     int filename_idx, enum vpnhide_hook_idx hook_idx)
{
	struct path_oracle_data *data;
	int dfd;
	const char __user *filename;
	char path_buf[512];
	bool uses_wrapper = false;

	if (!is_hook_active(hook_idx))
		return 1;

	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->should_deny = false;

	switch (hook_idx) {
	case HOOK_OPENAT:
		uses_wrapper = sys_openat_uses_wrapper;
		break;
	case HOOK_OPENAT2:
		uses_wrapper = sys_openat2_uses_wrapper;
		break;
	case HOOK_FACCESSAT:
		uses_wrapper = sys_faccessat_uses_wrapper;
		break;
	case HOOK_FACCESSAT2:
		uses_wrapper = sys_faccessat2_uses_wrapper;
		break;
	case HOOK_NEWFSTATAT:
		uses_wrapper = sys_newfstatat_uses_wrapper;
		break;
	case HOOK_READLINKAT:
		uses_wrapper = sys_readlinkat_uses_wrapper;
		break;
	default:
		break;
	}

	/* To be safe on ARM64, we check regs->regs[0] for wrapper since all syscall wrappers pack pt_regs */
	if (uses_wrapper) {
		struct pt_regs *user_regs = (struct pt_regs *)regs->regs[0];
		if (user_regs &&
		    (unsigned long)user_regs >= 0xFFFF000000000000ULL) {
			dfd = (int)user_regs->regs[dfd_idx];
			filename = (const char __user *)
					   user_regs->regs[filename_idx];
		} else {
			return 1;
		}
	} else {
		dfd = (int)regs->regs[dfd_idx];
		filename = (const char __user *)regs->regs[filename_idx];
	}

	if (get_path_from_dfd_and_name(dfd, filename, path_buf,
				       sizeof(path_buf)) == 0) {
		if (vh_is_resolved_path_guarded_vpn(path_buf)) {
			data->should_deny = true;
			vpnhide_dbg(
				"path_oracle_entry: matched guarded vpn path '%s', denying\n",
				path_buf);
			return 0;
		}
	}

	return 1;
}

static int path_oracle_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct path_oracle_data *data = (void *)ri->data;
	if (data->should_deny) {
		regs_set_return_value(regs, -ENOENT);
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      2);
	}
	return 0;
}

static int sys_openat_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	return path_oracle_entry(ri, regs, 0, 1, HOOK_OPENAT);
}
static int sys_openat_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	return path_oracle_ret(ri, regs);
}
static struct kretprobe sys_openat_krp = {
	.entry_handler = sys_openat_entry,
	.handler = sys_openat_ret,
	.data_size = sizeof(struct path_oracle_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_openat",
};

static int sys_openat2_entry(struct kretprobe_instance *ri,
			     struct pt_regs *regs)
{
	return path_oracle_entry(ri, regs, 0, 1, HOOK_OPENAT2);
}
static int sys_openat2_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	return path_oracle_ret(ri, regs);
}
static struct kretprobe sys_openat2_krp = {
	.entry_handler = sys_openat2_entry,
	.handler = sys_openat2_ret,
	.data_size = sizeof(struct path_oracle_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_openat2",
};

static int sys_faccessat_entry(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	return path_oracle_entry(ri, regs, 0, 1, HOOK_FACCESSAT);
}
static int sys_faccessat_ret(struct kretprobe_instance *ri,
			     struct pt_regs *regs)
{
	return path_oracle_ret(ri, regs);
}
static struct kretprobe sys_faccessat_krp = {
	.entry_handler = sys_faccessat_entry,
	.handler = sys_faccessat_ret,
	.data_size = sizeof(struct path_oracle_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_faccessat",
};

static int sys_faccessat2_entry(struct kretprobe_instance *ri,
				struct pt_regs *regs)
{
	return path_oracle_entry(ri, regs, 0, 1, HOOK_FACCESSAT2);
}
static int sys_faccessat2_ret(struct kretprobe_instance *ri,
			      struct pt_regs *regs)
{
	return path_oracle_ret(ri, regs);
}
static struct kretprobe sys_faccessat2_krp = {
	.entry_handler = sys_faccessat2_entry,
	.handler = sys_faccessat2_ret,
	.data_size = sizeof(struct path_oracle_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_faccessat2",
};

static int sys_newfstatat_entry(struct kretprobe_instance *ri,
				struct pt_regs *regs)
{
	return path_oracle_entry(ri, regs, 0, 1, HOOK_NEWFSTATAT);
}
static int sys_newfstatat_ret(struct kretprobe_instance *ri,
			      struct pt_regs *regs)
{
	return path_oracle_ret(ri, regs);
}
static struct kretprobe sys_newfstatat_krp = {
	.entry_handler = sys_newfstatat_entry,
	.handler = sys_newfstatat_ret,
	.data_size = sizeof(struct path_oracle_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_newfstatat",
};

static int sys_readlinkat_entry(struct kretprobe_instance *ri,
				struct pt_regs *regs)
{
	return path_oracle_entry(ri, regs, 0, 1, HOOK_READLINKAT);
}
static int sys_readlinkat_ret(struct kretprobe_instance *ri,
			      struct pt_regs *regs)
{
	return path_oracle_ret(ri, regs);
}
static struct kretprobe sys_readlinkat_krp = {
	.entry_handler = sys_readlinkat_entry,
	.handler = sys_readlinkat_ret,
	.data_size = sizeof(struct path_oracle_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "__arm64_sys_readlinkat",
};

/* ================================================================== */
/*  Hook 27: udp_sendmsg — non-blocking UDP queue pressure emul.      */
/* ================================================================== */

struct udp_sendmsg_data {
	struct sock *sk;
	int orig_sndbuf;
	bool spoofed;
};

#define BUCKET_CAPACITY 250
#define TOKEN_REGEN_NS 2000000ULL /* 2 milliseconds per token (500 pkts/s) */

struct udp_uid_rate {
	uid_t uid;
	u64 last_time_ns;
	u32 tokens; /* Fixed-point: 1000 = 1 token */
};

static struct udp_uid_rate udp_rates[MAX_TARGET_UIDS];
static DEFINE_SPINLOCK(udp_rates_lock);

static bool udp_rate_limit_exceeded(uid_t uid)
{
	u64 now = ktime_get_ns();
	int i;
	bool limit_exceeded = false;
	unsigned long flags;

	spin_lock_irqsave(&udp_rates_lock, flags);
	for (i = 0; i < MAX_TARGET_UIDS; i++) {
		if (udp_rates[i].uid == uid) {
			if (now > udp_rates[i].last_time_ns) {
				u64 elapsed = now - udp_rates[i].last_time_ns;
				u64 reg_tokens =
					(elapsed * 1000ULL) / TOKEN_REGEN_NS;
				if (reg_tokens > 0) {
					udp_rates[i].tokens += (u32)reg_tokens;
					if (udp_rates[i].tokens >=
					    BUCKET_CAPACITY * 1000) {
						udp_rates[i].tokens =
							BUCKET_CAPACITY * 1000;
						udp_rates[i].last_time_ns = now;
					} else {
						udp_rates[i].last_time_ns +=
							(reg_tokens *
							 TOKEN_REGEN_NS) /
							1000ULL;
					}
				}
			}

			if (udp_rates[i].tokens >= 1000) {
				udp_rates[i].tokens -= 1000;
				limit_exceeded = false;
			} else {
				limit_exceeded = true;
			}
			break;
		}
	}
	if (i == MAX_TARGET_UIDS) {
		for (i = 0; i < MAX_TARGET_UIDS; i++) {
			if (udp_rates[i].uid == 0) {
				udp_rates[i].uid = uid;
				udp_rates[i].last_time_ns = now;
				udp_rates[i].tokens =
					(BUCKET_CAPACITY - 1) * 1000;
				limit_exceeded = false;
				break;
			}
		}
	}
	spin_unlock_irqrestore(&udp_rates_lock, flags);
	return limit_exceeded;
}

static int udp_sendmsg_entry(struct kretprobe_instance *ri,
			     struct pt_regs *regs)
{
	struct udp_sendmsg_data *data;
	struct sock *sk;
	struct msghdr *msg;
	uid_t uid;

	if (!is_hook_active(HOOK_UDP_SENDMSG))
		return 1;

	uid = from_kuid(&init_user_ns, current_uid());
	if (!is_target_uid_val(uid))
		return 1;

	sk = (struct sock *)regs->regs[0];
	msg = (struct msghdr *)regs->regs[1];

	if (!sk || !msg)
		return 1;

	data = (void *)ri->data;
	data->sk = sk;
	data->orig_sndbuf = sk->sk_sndbuf;
	data->spoofed = false;

	if (msg->msg_flags & MSG_DONTWAIT) {
		if (udp_rate_limit_exceeded(uid)) {
			sk->sk_sndbuf = 0;
			data->spoofed = true;
			udelay(50);
		}
	}

	return 0;
}

static int udp_sendmsg_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct udp_sendmsg_data *data = (void *)ri->data;

	if (data->spoofed && data->sk)
		data->sk->sk_sndbuf = data->orig_sndbuf;

	return 0;
}

static struct kretprobe udp_sendmsg_krp = {
	.entry_handler = udp_sendmsg_entry,
	.handler = udp_sendmsg_ret,
	.data_size = sizeof(struct udp_sendmsg_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "udp_sendmsg",
};

/* ================================================================== */
/*  Module init / exit                                                */
/* ================================================================== */

/* ================================================================== */
/*  Hook 30: fib_trie_seq_show — /proc/net/fib_trie                   */
/*  Defensive filter: standard fib_trie output does not include       */
/*  interface names, so this hook is a no-op on stock kernels.        */
/*  It guards against vendor patches that emit "dev <ifname>" tokens. */
/* ================================================================== */

struct fib_trie_data {
	struct seq_file *seq;
	size_t start_count;
};

static int fib_trie_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_trie_data *data;

	if (!is_hook_active(HOOK_FIB_TRIE))
		return 1;
	if (!is_target_uid())
		return 1;

	data = (void *)ri->data;
	data->seq = (struct seq_file *)regs->regs[0];
	data->start_count = data->seq ? data->seq->count : 0;
	return 0;
}

static int fib_trie_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_trie_data *data = (void *)ri->data;
	struct seq_file *seq = data->seq;
	char *buf, *src, *dst, *end;

	if (!seq || !seq->buf)
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
		char word[IFNAMSIZ];
		char *p = src;
		bool has_vpn = false;

		/* Scan each whitespace-separated word in the line */
		while (p < line_end && !has_vpn) {
			int j = 0;

			while (p < line_end &&
			       (*p == ' ' || *p == '\t' || *p == '|' ||
				*p == '+' || *p == '-'))
				p++;
			while (p < line_end && j < IFNAMSIZ - 1 && *p != ' ' &&
			       *p != '\t' && *p != '\n' && *p != '/' &&
			       *p != '|')
				word[j++] = *p++;
			word[j] = '\0';
			if (j > 0 && is_vpn_ifname(word))
				has_vpn = true;
		}

		if (has_vpn) {
			vpnhide_dbg(
				"fib_trie_ret: suppressing line with VPN iface\n");
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

static struct kretprobe fib_trie_krp = {
	.handler = fib_trie_ret,
	.entry_handler = fib_trie_entry,
	.data_size = sizeof(struct fib_trie_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "fib_trie_seq_show",
};

/* ================================================================== */
/*  Hook 31: tc_fill_qdisc — filter RTM_GETQDISC responses            */
/*  After discovering a hidden ifindex via brute-force, an attacker   */
/*  can query RTM_GETQDISC for that ifindex and fingerprint the VPN   */
/*  (TUN always returns "pfifo_fast"). We trim the skb entry the      */
/*  same way rtnl_fill_ifinfo is trimmed for RTM_GETLINK.             */
/*                                                                    */
/*  tc_fill_qdisc(skb, q, d, event, portid, seq, flags, err_skb)     */
/*  arm64: x0=skb                                                     */
/*  tcmsg layout: family(1)+pad1(1)+pad2(2)+ifindex(4) = ifindex@4   */
/* ================================================================== */

struct tc_fill_qdisc_data {
	struct sk_buff *skb;
	int saved_len;
};

static int tc_fill_qdisc_entry(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	struct tc_fill_qdisc_data *data;
	struct sk_buff *skb;

	if (!is_hook_active(HOOK_TC_FILL_QDISC))
		return 1;
	if (!is_target_uid())
		return 1;

	skb = (struct sk_buff *)regs->regs[0];
	data = (void *)ri->data;
	data->skb = skb;
	data->saved_len = skb ? skb->len : 0;
	return 0;
}

static int tc_fill_qdisc_ret(struct kretprobe_instance *ri,
			     struct pt_regs *regs)
{
	struct tc_fill_qdisc_data *data = (void *)ri->data;
	struct sk_buff *skb = data->skb;
	/* nlmsghdr(16) + tcmsg.family(1)+pad1(1)+pad2(2) = ifindex at +20 */
	const size_t ifindex_off = sizeof(struct nlmsghdr) + 4;
	int ifindex = 0;

	if (regs_return_value(regs) != 0)
		return 0;
	if (!skb || !skb->data)
		return 0;
	if (skb->len < data->saved_len + (int)(ifindex_off + sizeof(int)))
		return 0;

	if (copy_from_kernel_nofault(&ifindex,
				     skb->data + data->saved_len + ifindex_off,
				     sizeof(ifindex)) != 0)
		return 0;

	if (ifindex > 0 && is_active_vpn_ifindex((u32)ifindex)) {
		vpnhide_dbg(
			"tc_fill_qdisc_ret: hiding qdisc for VPN ifindex=%d\n",
			ifindex);
		skb_trim(skb, data->saved_len);
		record_kmod_intercept(from_kuid(&init_user_ns, current_uid()),
				      1);
	}
	return 0;
}

static struct kretprobe tc_fill_qdisc_krp = {
	.handler = tc_fill_qdisc_ret,
	.entry_handler = tc_fill_qdisc_entry,
	.data_size = sizeof(struct tc_fill_qdisc_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "tc_fill_qdisc",
};

/* Stealthy IOCTL interface replaces /proc files */

struct kretprobe_reg {
	struct kretprobe *krp;
	const char *name;
	const char *fallback;
	bool registered;
	int primary_idx;
};

static struct kretprobe sock_ioctl_krp = {
	.handler = sock_ioctl_ret,
	.entry_handler = sock_ioctl_entry,
	.data_size = sizeof(struct sock_ioctl_data),
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "sock_ioctl",
};

static struct kretprobe_reg probes[] = {
	{ &dev_ioctl_krp, "dev_ioctl", NULL, false, -1 },
	{ &sock_ioctl_krp, "sock_ioctl", NULL, false, -1 },
	{ &rtnl_fill_krp, "rtnl_fill_ifinfo", NULL, false, -1 },
	{ &inet6_fill_krp, "inet6_fill_ifaddr", NULL, false, -1 },
	{ &inet_fill_krp, "inet_fill_ifaddr", NULL, false, -1 },
	{ &fib_route_krp, "fib_route_seq_show", NULL, false, -1 },
	{ &ipv6_route_krp, "ipv6_route_seq_show", NULL, false, -1 },
	{ &fib_dump_krp, "fib_dump_info", NULL, false, -1 },
	{ &fib_rule_fill_krp, "fib_nl_fill_rule", NULL, false, -1 },
	{ &rt6_fill_krp, "rt6_fill_node", NULL, false, -1 },
	{ &rt_fill_krp, "rt_fill_info", NULL, false, -1 },
	{ &sys_setsockopt_krp, "__arm64_sys_setsockopt", NULL, false, -1 },
	{ &sys_getsockopt_krp, "__arm64_sys_getsockopt", NULL, false, -1 },
	{ &sk_getsockopt_krp, "sk_getsockopt", NULL, false, 12 },
	{ &sock_getsockopt_krp, "sock_getsockopt", NULL, false, 12 },
	{ &sock_common_getsockopt_krp, "sock_common_getsockopt", NULL, false,
	  12 },
	{ &socket_connect_krp, "__arm64_sys_connect", NULL, false, -1 },
	{ &socket_bind_krp, "__arm64_sys_bind", NULL, false, -1 },
	{ &socket_connect_krp, "security_socket_connect", NULL, false, 16 },
	{ &socket_bind_krp, "security_socket_bind", NULL, false, 17 },
	{ &inet6_bind_ll_krp, "inet6_bind", NULL, false, -1 },
	{ &sys_getsockname_krp, "__arm64_sys_getsockname", NULL, false, -1 },
	{ &inet_getname_krp, "inet_getname", NULL, false, 20 },
	{ &inet6_getname_krp, "inet6_getname", NULL, false, 20 },
	{ &sys_bpf_krp, "__arm64_sys_bpf", NULL, false, -1 },
	{ &sys_getdents64_krp, "__arm64_sys_getdents64", NULL, false, -1 },
	{ &dev_seq_krp, "dev_seq_show", NULL, false, -1 },
	{ &if6_seq_krp, "if6_seq_show", NULL, false, -1 },
	{ &sys_openat_krp, "__arm64_sys_openat", NULL, false, -1 },
	{ &sys_openat2_krp, "__arm64_sys_openat2", NULL, false, -1 },
	{ &sys_faccessat_krp, "__arm64_sys_faccessat", NULL, false, -1 },
	{ &sys_faccessat2_krp, "__arm64_sys_faccessat2", NULL, false, -1 },
	{ &sys_newfstatat_krp, "__arm64_sys_newfstatat", NULL, false, -1 },
	{ &sys_readlinkat_krp, "__arm64_sys_readlinkat", NULL, false, -1 },
	{ &udp_sendmsg_krp, "udp_sendmsg", NULL, false, -1 },
	{ &udpv6_sendmsg_ll_krp, "udpv6_sendmsg", NULL, false, -1 },
	{ &fib_trie_krp, "fib_trie_seq_show", NULL, false, -1 },
	{ &tc_fill_qdisc_krp, "tc_fill_qdisc", NULL, false, -1 },
};

static int __init vpnhide_init(void)
{
	int i, ret, ok = 0;

	if (sys_setsockopt_krp.kp.symbol_name &&
	    strcmp(sys_setsockopt_krp.kp.symbol_name,
		   "__arm64_sys_setsockopt") == 0) {
		sys_setsockopt_uses_wrapper = true;
	}
	if (sys_getsockopt_krp.kp.symbol_name &&
	    strcmp(sys_getsockopt_krp.kp.symbol_name,
		   "__arm64_sys_getsockopt") == 0) {
		sys_getsockopt_uses_wrapper = true;
	}
	if (sys_bpf_krp.kp.symbol_name &&
	    strcmp(sys_bpf_krp.kp.symbol_name, "__arm64_sys_bpf") == 0) {
		sys_bpf_uses_wrapper = true;
	}
	if (socket_connect_krp.kp.symbol_name &&
	    strcmp(socket_connect_krp.kp.symbol_name, "__arm64_sys_connect") ==
		    0) {
		sys_connect_uses_wrapper = true;
	}
	if (socket_bind_krp.kp.symbol_name &&
	    strcmp(socket_bind_krp.kp.symbol_name, "__arm64_sys_bind") == 0) {
		sys_bind_uses_wrapper = true;
	}
	if (sys_getsockname_krp.kp.symbol_name &&
	    strcmp(sys_getsockname_krp.kp.symbol_name,
		   "__arm64_sys_getsockname") == 0) {
		sys_getsockname_uses_wrapper = true;
	}
	if (sys_getdents64_krp.kp.symbol_name &&
	    strcmp(sys_getdents64_krp.kp.symbol_name,
		   "__arm64_sys_getdents64") == 0) {
		sys_getdents64_uses_wrapper = true;
	}
	if (sys_openat_krp.kp.symbol_name &&
	    strcmp(sys_openat_krp.kp.symbol_name, "__arm64_sys_openat") == 0) {
		sys_openat_uses_wrapper = true;
	}
	if (sys_openat2_krp.kp.symbol_name &&
	    strcmp(sys_openat2_krp.kp.symbol_name, "__arm64_sys_openat2") ==
		    0) {
		sys_openat2_uses_wrapper = true;
	}
	if (sys_faccessat_krp.kp.symbol_name &&
	    strcmp(sys_faccessat_krp.kp.symbol_name, "__arm64_sys_faccessat") ==
		    0) {
		sys_faccessat_uses_wrapper = true;
	}
	if (sys_faccessat2_krp.kp.symbol_name &&
	    strcmp(sys_faccessat2_krp.kp.symbol_name,
		   "__arm64_sys_faccessat2") == 0) {
		sys_faccessat2_uses_wrapper = true;
	}
	if (sys_newfstatat_krp.kp.symbol_name &&
	    strcmp(sys_newfstatat_krp.kp.symbol_name,
		   "__arm64_sys_newfstatat") == 0) {
		sys_newfstatat_uses_wrapper = true;
	}
	if (sys_readlinkat_krp.kp.symbol_name &&
	    strcmp(sys_readlinkat_krp.kp.symbol_name,
		   "__arm64_sys_readlinkat") == 0) {
		sys_readlinkat_uses_wrapper = true;
	}

	/* Initialize RCU targets pointers */
	rcu_assign_pointer(global_targets, NULL);
	rcu_assign_pointer(global_port_targets, NULL);

	for (i = 0; i < ARRAY_SIZE(probes); i++) {
		if (strcmp(probes[i].name, "sock_getsockopt") == 0) {
			int j;
			bool skip = false;
			for (j = 0; j < ARRAY_SIZE(probes); j++) {
				if (strcmp(probes[j].name, "sk_getsockopt") ==
				    0) {
					if (probes[j].registered) {
						skip = true;
					}
					break;
				}
			}
			if (skip) {
				pr_warn("kretprobe(%s) skipped because sk_getsockopt is registered\n",
					probes[i].name);
				continue;
			}
		}

		if (probes[i].primary_idx >= 0) {
			int p_idx = probes[i].primary_idx;
			if (probes[p_idx].registered) {
				pr_warn("kretprobe(%s) skipped because primary kretprobe(%s) is registered\n",
					probes[i].name, probes[p_idx].name);
				continue;
			}
		}
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
	struct vpnhide_active_vpns *vpns;
	struct vpnhide_spoof_ip_rcu *old_sip;
	struct vpnhide_iface_prefixes *old_p;
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

	/* Cleanup RCU LSPosed targets */
	{
		struct vpnhide_targets *t_lsposed;
		spin_lock(&lsposed_targets_update_lock);
		t_lsposed = rcu_dereference_protected(
			global_lsposed_targets,
			lockdep_is_held(&lsposed_targets_update_lock));
		rcu_assign_pointer(global_lsposed_targets, NULL);
		spin_unlock(&lsposed_targets_update_lock);

		if (t_lsposed) {
			synchronize_rcu();
			kfree(t_lsposed);
		}
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

	/* Cleanup RCU active vpns */
	spin_lock(&active_vpns_lock);
	vpns = rcu_dereference_protected(global_active_vpns,
					 lockdep_is_held(&active_vpns_lock));
	rcu_assign_pointer(global_active_vpns, NULL);
	spin_unlock(&active_vpns_lock);

	if (vpns) {
		synchronize_rcu();
		kfree(vpns);
	}

	/* Cleanup RCU name cache */
	{
		struct vh_vpn_name_cache *old_c;
		spin_lock(&g_vpn_name_cache_lock);
		old_c = rcu_dereference_protected(
			g_vpn_name_cache,
			lockdep_is_held(&g_vpn_name_cache_lock));
		rcu_assign_pointer(g_vpn_name_cache, NULL);
		spin_unlock(&g_vpn_name_cache_lock);

		if (old_c) {
			synchronize_rcu();
			kfree(old_c);
		}
	}

	/* Cleanup RCU spoof IP */
	spin_lock(&spoof_ip_lock);
	old_sip = rcu_dereference_protected(global_spoof_ip,
					    lockdep_is_held(&spoof_ip_lock));
	rcu_assign_pointer(global_spoof_ip, NULL);
	spin_unlock(&spoof_ip_lock);

	if (old_sip) {
		synchronize_rcu();
		kfree(old_sip);
	}

	/* Cleanup RCU iface prefixes */
	spin_lock(&iface_prefixes_lock);
	old_p = rcu_dereference_protected(
		global_iface_prefixes, lockdep_is_held(&iface_prefixes_lock));
	rcu_assign_pointer(global_iface_prefixes, NULL);
	spin_unlock(&iface_prefixes_lock);

	if (old_p) {
		synchronize_rcu();
		kfree(old_p);
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
