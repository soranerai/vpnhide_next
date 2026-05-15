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
#include <linux/mutex.h>
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
#include <net/ip6_fib.h>
#include <net/ip6_route.h>
#include <net/route.h>
#include <linux/socket.h>
#include <linux/in.h>
#include <linux/in6.h>
#include <net/ipv6.h>

#include "generated/iface_lists.h"
#include "include/vpnhide.h"

#ifndef CONFIG_ARM64
#error "vpnhide_kmod currently supports only arm64 (handlers read regs->regs[N] directly)"
#endif

#define MODNAME "vpnhide"

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

#define is_vpn_ifname(name) vpnhide_is_vpn_ifname(name)

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
static DEFINE_MUTEX(targets_update_lock);

static struct vpnhide_port_targets __rcu *global_port_targets;
static DEFINE_MUTEX(port_targets_update_lock);

struct vpnhide_iface_prefixes {
	int count;
	char prefixes[MAX_IFACE_PREFIXES][MAX_IFACE_LEN];
	struct rcu_head rcu;
};

static struct vpnhide_iface_prefixes __rcu *global_iface_prefixes;
static DEFINE_MUTEX(iface_prefixes_lock);

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

static bool is_target_uid(void)
{
	uid_t uid = from_kuid(&init_user_ns, current_uid());
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

/* ================================================================== */
/*  Hook 1: dev_ioctl — all per-interface ioctls                      */
/*                                                                    */
/*  dev_ioctl() on GKI 6.1:                                          */
/*    int dev_ioctl(struct net *net, unsigned int cmd,                */
/*                  struct ifreq *ifr, void __user *data,            */
/*                  bool *need_copyout)                               */
/*  arm64: x0=net, x1=cmd, x2=ifr (KERNEL ptr), x3=data (__user)   */
/*                                                                    */
/*  Covers SIOCGIFFLAGS, SIOCGIFNAME, SIOCGIFMTU, SIOCGIFINDEX,     */
/*  SIOCGIFHWADDR, SIOCGIFADDR, and any other cmd that goes through  */
/*  dev_ioctl with a VPN interface name in ifr_name. Returns ENODEV  */
/*  for all of them.                                                  */
/*                                                                    */
/*  Note: SIOCGIFCONF goes through sock_ioctl -> dev_ifconf, not     */
/*  through dev_ioctl, so it is not covered here.                    */
/* ================================================================== */

struct dev_ioctl_data {
	unsigned int cmd;
	struct ifreq *kifr; /* kernel pointer, saved from x2 */
	bool active; /* true = caller is target UID, run ret handler */
};

static int dev_ioctl_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct dev_ioctl_data *data = (void *)ri->data;

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
/*                                                                    */
/*  Why sock_ioctl instead of dev_ifconf?                             */
/*                                                                    */
/*  On GKI 5.10 kernels built with Clang LTO (all stock Android       */
/*  devices), the linker inlines dev_ifconf() into sock_do_ioctl().   */
/*  The symbol "dev_ifconf" stays in kallsyms as a dead stub, so      */
/*  kretprobe registration succeeds but the probe never fires.        */
/*  Confirmed by disassembly on Xiaomi 13 Lite (5.10.136) and Lenovo  */
/*  Legion 2 Pro (5.10.101): no `bl dev_ifconf` in sock_do_ioctl.    */
/*                                                                    */
/*  On 6.1+, SIOCGIFCONF was moved out of sock_do_ioctl() into       */
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
/*  sock_ioctl(struct file *file, unsigned int cmd, unsigned long arg) */
/*  arm64: x0=file, x1=cmd, x2=arg (__user ptr)                      */
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
		vpnhide_dbg("ifconf filtered %d -> %d bytes\n", orig_len,
			    ifc.ifc_len);
	}

	return 0;
}

/* ================================================================== */
/*  Hook 2b: sock_setsockopt — Aikido Bind Sabotage                   */
/*                                                                    */
/*  sock_setsockopt(struct socket *sock, int level, int optname,      */
/*                  sockptr_t optval, unsigned int optlen)            */
/*                                                                    */
/*  If a target app tries to SO_BINDTODEVICE or SO_BINDTOIFINDEX to   */
/*  a VPN interface, we sabotage the arguments on the fly. We change  */
/*  optlen to 0. The kernel interprets this as "remove binding", does */
/*  nothing harmful, and returns 0 (Success) to the app.              */
/* ================================================================== */

static int sock_setsockopt_entry(struct kretprobe_instance *ri,
				 struct pt_regs *regs)
{
	int level = (int)regs->regs[1];
	int optname = (int)regs->regs[2];
	void __user *optval_ptr = (void __user *)regs->regs[3];
	bool is_kernel = (regs->regs[4] & 1); /* sockptr_t.is_kernel */
	int optlen = (int)regs->regs[5];
	char name[IFNAMSIZ];

	if (!is_target_uid())
		return 0;

	if (level != SOL_SOCKET)
		return 0;

	if (is_kernel)
		return 0;

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
				"sock_setsockopt: spoofing SO_BINDTODEVICE to %s\n",
				name);
			regs->regs[5] = 0;
		}
	} else if (optname == SO_BINDTOIFINDEX) {
		int ifindex;
		struct net_device *dev;
		struct net *net;

		if (optlen != sizeof(int))
			return 0;
		if (copy_from_user(&ifindex, optval_ptr, sizeof(int)))
			return 0;

		if (ifindex <= 0)
			return 0;

		net = current->nsproxy->net_ns;
		rcu_read_lock();
		dev = dev_get_by_index_rcu(net, ifindex);
		if (dev && is_vpn_ifname(dev->name)) {
			vpnhide_dbg(
				"sock_setsockopt: spoofing SO_BINDTOIFINDEX %d (%s)\n",
				ifindex, dev->name);
			regs->regs[2] = SO_BINDTODEVICE;
			regs->regs[5] = 0;
		}
		rcu_read_unlock();
	}

	return 0;
}

static struct kretprobe sock_setsockopt_krp = {
	.entry_handler = sock_setsockopt_entry,
	.maxactive = VPNHIDE_KRETPROBE_MAXACTIVE,
	.kp.symbol_name = "sock_setsockopt",
};

/* ================================================================== */
/*  Hook 3: rtnl_fill_ifinfo — netlink RTM_NEWLINK (getifaddrs path)  */
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
	struct rtnl_fill_data *data = (void *)ri->data;
	struct net_device *dev;

	data->should_filter = false;

	if (!is_target_uid()) {
		vpnhide_dbg("rtnl_fill_entry: uid=%u target=0\n",
			    from_kuid(&init_user_ns, current_uid()));
		return 0;
	}

	/*
	 * rtnl_fill_ifinfo(struct sk_buff *skb, struct net_device *dev, ...)
	 * arm64: x0=skb, x1=dev
	 */
	dev = (struct net_device *)regs->regs[1];
	/* Callers hold RTNL which protects dev->name, but take RCU as
	 * belt-and-suspenders — same rationale as inet6_fill_entry. */
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
	/* Undo whatever the fill function wrote to the skb */
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
/*  Hook 4: inet6_fill_ifaddr — RTM_GETADDR IPv6 (getifaddrs path)   */
/*                                                                    */
/*  inet6_fill_ifaddr(struct sk_buff *skb, struct inet6_ifaddr *ifa,  */
/*                    struct inet6_fill_args *args)                   */
/*  arm64: x0=skb, x1=ifa                                           */
/*                                                                    */
/*  getifaddrs() does RTM_GETLINK (filtered by hook 3) then          */
/*  RTM_GETADDR. Addresses for VPN interfaces still appear in        */
/*  RTM_GETADDR, so bionic reconstructs a tun0 entry with flags=0.  */
/*  Filtering here prevents that.                                    */
/*                                                                    */
/*  We can't return -EMSGSIZE (causes infinite retry on empty skb).  */
/*  Instead, save skb->len before and trim the skb back on return,   */
/*  making it look like the entry was never written. Return 0.       */
/* ================================================================== */

struct inet6_fill_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int inet6_fill_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct inet6_fill_data *data = (void *)ri->data;
	struct inet6_ifaddr *ifa;

	data->should_filter = false;

	if (!is_target_uid())
		return 0;

	ifa = (struct inet6_ifaddr *)regs->regs[1];
	/*
	 * The callers of inet6_fill_ifaddr() hold either rcu_read_lock()
	 * (netlink dump path) or RTNL. We take rcu_read_lock() explicitly
	 * so the kretprobe handler doesn't rely on that implicit guarantee.
	 */
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
	/* Undo whatever the fill function wrote to the skb */
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
/*  Hook 5: inet_fill_ifaddr — RTM_GETADDR IPv4 (getifaddrs path)    */
/*                                                                    */
/*  inet_fill_ifaddr(struct sk_buff *skb, struct in_ifaddr *ifa,     */
/*                   struct inet_fill_args *args)                    */
/*  arm64: x0=skb, x1=ifa                                           */
/*  Same skb-trim approach as hook 4.                                */
/* ================================================================== */

struct inet_fill_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int inet_fill_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct inet_fill_data *data = (void *)ri->data;
	struct in_ifaddr *ifa;

	data->should_filter = false;

	if (!is_target_uid())
		return 0;

	ifa = (struct in_ifaddr *)regs->regs[1];
	/* Same RCU rationale as inet6_fill_entry above. */
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
/*                                                                    */
/*  fib_route_seq_show(struct seq_file *seq, void *v) writes one or  */
/*  more tab-separated route lines into seq->buf, each ending with   */
/*  '\n'. The first field is the interface name.                      */
/*                                                                    */
/*  We save seq and seq->count on entry. In the return handler we    */
/*  scan what was written, compact out VPN lines, and adjust count.  */
/* ================================================================== */

struct fib_route_data {
	struct seq_file *seq;
	size_t start_count;
	bool target;
};

static int fib_route_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data = (void *)ri->data;

	/*
	 * arm64: x0 = seq_file*, x1 = v (iterator element).
	 * Save seq pointer and current buffer position so the
	 * return handler knows where this call's output begins.
	 */
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

/*
 * We access seq->buf and seq->count without seq_file's internal mutex.
 * This is safe because seq_read() drives the ->show() callback
 * synchronously under its own fd context — no concurrent access to
 * the same seq_file is possible between our entry and return handlers.
 */
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

	/*
	 * Scan the region [start_count, seq->count) for lines whose
	 * first tab-separated field is a VPN interface name. Compact
	 * out matching lines in place and adjust seq->count.
	 *
	 * Each route line looks like: "tun0\t08000000\t...\n"
	 */
	buf = seq->buf;
	src = buf + data->start_count;
	dst = src;
	end = buf + seq->count;

	while (src < end) {
		char *nl = memchr(src, '\n', end - src);
		char *line_end = nl ? nl + 1 : end;
		size_t line_len = line_end - src;

		/* Extract the interface name (first field, tab-delimited) */
		for (j = 0; j < IFNAMSIZ - 1 && j < (int)line_len &&
			    src[j] != '\t' && src[j] != '\n';
		     j++)
			ifname[j] = src[j];
		ifname[j] = '\0';

		if (is_vpn_ifname(ifname)) {
			vpnhide_dbg("fib_route_ret: hiding route for %s\n",
				    ifname);
			/* Skip this line */
			src = line_end;
			continue;
		}

		/* Keep this line — move it down if there's a gap */
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
/*                                                                    */
/*  fib_dump_info(skb, portid, seq, event, fri, flags)                */
/*  arm64: x0=skb, x4=fri (struct fib_rt_info*)                       */
/* ================================================================== */

struct fib_dump_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int fib_dump_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_dump_data *data = (void *)ri->data;
	struct fib_info *fi = NULL;

	data->should_filter = false;

	if (!is_target_uid())
		return 0;

	/* x0=skb, x4=fi (or fri in 6.1+) */
	data->skb = (struct sk_buff *)regs->regs[0];

#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 15, 0)
	{
		struct fib_rt_info *fri = (struct fib_rt_info *)regs->regs[4];
		if (fri)
			fi = fri->fi;
	}
#else
	/* GKI 5.10 mapping: x4 is tb_id, fi is on stack (arg 10).
	 * We don't try to pull from stack here; fi will remain NULL.
	 * This means bulk dumps (ip route) won't hide VPN routes on 5.10
	 * via this hook, but rtnl_fill_ifinfo still hides the interfaces. */
#endif

	rcu_read_lock();
	if (fi && fi->fib_nhs > 0) {
		/* Access first nexthop interface */
		struct net_device *dev = fi->fib_nh[0].nh_common.nhc_dev;
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
/*  Hook 8: rt6_fill_node — IPv6 routes                                */
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
	struct rt6_fill_data *data = (void *)ri->data;
	struct fib6_info *rt;
	struct dst_entry *dst;

	data->should_filter = false;

	if (!is_target_uid())
		return 0;

	/*
	 * rt6_fill_node(net, skb, rt, dst, ...)
	 * arm64: x0=net, x1=skb, x2=rt, x3=dst
	 *
	 * In most route dumps, dst is NULL and information is in rt.
	 */
	data->skb = (struct sk_buff *)regs->regs[1];
	rt = (struct fib6_info *)regs->regs[2];
	dst = (struct dst_entry *)regs->regs[3];

	rcu_read_lock();
	if (rt) {
		struct net_device *dev = NULL;
		dev = rt->fib6_nh->nh_common.nhc_dev;
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
/*  Hook 8b: ipv6_route_seq_show — /proc/net/ipv6_route                */
/*                                                                    */
/*  ipv6_route_seq_show(seq, v) is the IPv6 equivalent of hook 6.     */
/*  The interface name is the LAST field in the line.                 */
/* ================================================================== */

static int ipv6_route_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct fib_route_data *data = (void *)ri->data;

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

		/* Interface name is the last field: "dest ... flags ifname\n" */
		p = line_end - 1;
		while (p >= src &&
		       (*p == '\n' || *p == '\r' || *p == ' ' || *p == '\t'))
			p--;

		/* Now p points to the end of ifname. Backtrack to start. */
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
/*                                                                    */
/*  rt_fill_info(skb, dst, src, rt, ...)                              */
/*  arm64: x0=skb, x3=rt (struct rtable*)                             */
/* ================================================================== */

struct rt_fill_data {
	struct sk_buff *skb;
	unsigned int saved_len;
	bool should_filter;
};

static int rt_fill_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct rt_fill_data *data = (void *)ri->data;
	struct net_device *dev = NULL;

	data->should_filter = false;

	if (!is_target_uid())
		return 0;

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 1, 0)
	/* GKI 6.1+ mapping: x0=skb, x3=fri */
	data->skb = (struct sk_buff *)regs->regs[0];
	{
		struct fib_rt_info *fri = (struct fib_rt_info *)regs->regs[3];
		if (fri && fri->fi && fri->fi->fib_nhs > 0)
			dev = fri->fi->fib_nh[0].nh_common.nhc_dev;
	}
#else
	/* GKI 5.10 / 5.15 mapping: x6=skb, x3=rt */
	data->skb = (struct sk_buff *)regs->regs[6];
	{
		struct rtable *rt = (struct rtable *)regs->regs[3];
		if (rt)
			dev = rt->dst.dev;
	}
#endif

	rcu_read_lock();
	if (dev && is_vpn_ifname(dev->name)) {
		data->saved_len = data->skb ? data->skb->len : 0;
		data->should_filter = true;
		vpnhide_dbg("rt_fill_entry: hiding route via %s\n", dev->name);
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
/*  UID List Update Logic                                              */
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

	mutex_lock(&port_targets_update_lock);
	old_t = rcu_dereference_protected(
		global_port_targets,
		lockdep_is_held(&port_targets_update_lock));
	rcu_assign_pointer(global_port_targets, new_t);
	mutex_unlock(&port_targets_update_lock);

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

	mutex_lock(&targets_update_lock);
	old_t = rcu_dereference_protected(
		global_targets, lockdep_is_held(&targets_update_lock));
	rcu_assign_pointer(global_targets, new_t);
	mutex_unlock(&targets_update_lock);

	if (old_t) {
		synchronize_rcu();
		kfree(old_t);
	}

	vpnhide_dbg("Normal targets updated: %d UIDs\n", count);
	return 0;
}

/* Handle configuration IOCTLs from root manager/app */
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
			/* Backward compatibility: if app sends just UIDs, block ALL loopback for them */
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
		pr_info(MODNAME ": debug logging %s\n",
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

	default:
		return -ENOIOCTLCMD;
	}

	return ret;
}

/* Misc device IOCTL wrapper */
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
	struct socket_connect_data *data = (void *)ri->data;
	struct socket *sock = (struct socket *)regs->regs[0];
	struct sockaddr *addr = (struct sockaddr *)regs->regs[1];
	uid_t uid = from_kuid(&init_user_ns, current_uid());
	struct vpnhide_port_targets *t;
	struct vpnhide_uid_port_rules *urules = NULL;
	int i;

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
		if (sin->sin_addr.s_addr == htonl(INADDR_LOOPBACK)) {
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
		if (ipv6_addr_loopback(&sin6->sin6_addr)) {
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

static int sock_ioctl_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct sock_ioctl_data *data = (void *)ri->data;
	unsigned int cmd = (unsigned int)regs->regs[1];
	unsigned long arg = (unsigned long)regs->regs[2];

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
/*  Module init / exit                                                */
/* ================================================================== */

/* Stealthy IOCTL interface replaces /proc files */

struct kretprobe_reg {
	struct kretprobe *krp;
	const char *name;
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
	{ &dev_ioctl_krp, "dev_ioctl", false },
	{ &sock_ioctl_krp, "sock_ioctl", false },
	{ &rtnl_fill_krp, "rtnl_fill_ifinfo", false },
	{ &inet6_fill_krp, "inet6_fill_ifaddr", false },
	{ &inet_fill_krp, "inet_fill_ifaddr", false },
	{ &fib_route_krp, "fib_route_seq_show", false },
	{ &ipv6_route_krp, "ipv6_route_seq_show", false },
	{ &fib_dump_krp, "fib_dump_info", false },
	{ &rt6_fill_krp, "rt6_fill_node", false },
	{ &rt_fill_krp, "rt_fill_info", false },
	{ &sock_setsockopt_krp, "sock_setsockopt", false },
	{ &socket_connect_krp, "security_socket_connect", false },
};

static int __init vpnhide_init(void)
{
	int i, ret, ok = 0;

	/* Initialize RCU targets pointers */
	rcu_assign_pointer(global_targets, NULL);
	rcu_assign_pointer(global_port_targets, NULL);
	rcu_assign_pointer(global_iface_prefixes, NULL);

	for (i = 0; i < ARRAY_SIZE(probes); i++) {
		ret = register_kretprobe(probes[i].krp);
		if (ret < 0) {
			pr_warn(MODNAME ": kretprobe(%s) failed: %d\n",
				probes[i].name, ret);
		} else {
			probes[i].registered = true;
			ok++;
			pr_info(MODNAME ": kretprobe(%s) registered\n",
				probes[i].name);
		}
	}

	ret = misc_register(&vpnhide_misc);
	if (ret) {
		pr_err(MODNAME ": failed to register misc device\n");
		/* Don't abort yet, kprobes might still work for passive hiding */
	}

	pr_info(MODNAME ": loaded\n");
	return 0;
}

static void __exit vpnhide_exit(void)
{
	struct vpnhide_targets *t;
	struct vpnhide_port_targets *t_port;
	int i;

	/* No proc entries to remove anymore */

	for (i = 0; i < ARRAY_SIZE(probes); i++) {
		if (probes[i].registered) {
			unregister_kretprobe(probes[i].krp);
			pr_info(MODNAME ": kretprobe(%s) unregistered "
					"(missed %d)\n",
				probes[i].name, probes[i].krp->nmissed);
		}
	}

	/* Cleanup RCU targets */
	mutex_lock(&targets_update_lock);
	t = rcu_dereference_protected(global_targets,
				      lockdep_is_held(&targets_update_lock));
	rcu_assign_pointer(global_targets, NULL);
	mutex_unlock(&targets_update_lock);

	if (t) {
		synchronize_rcu();
		kfree(t);
	}

	/* Cleanup RCU port targets */
	mutex_lock(&port_targets_update_lock);
	t_port = rcu_dereference_protected(
		global_port_targets,
		lockdep_is_held(&port_targets_update_lock));
	rcu_assign_pointer(global_port_targets, NULL);
	mutex_unlock(&port_targets_update_lock);

	if (t_port) {
		synchronize_rcu();
		kfree(t_port);
	}

	/* Cleanup RCU iface prefixes */
	mutex_lock(&iface_prefixes_lock);
	t = (struct vpnhide_targets *)rcu_dereference_protected(
		global_iface_prefixes, lockdep_is_held(&iface_prefixes_lock));
	rcu_assign_pointer(global_iface_prefixes, NULL);
	mutex_unlock(&iface_prefixes_lock);

	if (t) {
		synchronize_rcu();
		kfree(t);
	}

	misc_deregister(&vpnhide_misc);

	pr_info(MODNAME ": unloaded\n");
}

module_init(vpnhide_init);
module_exit(vpnhide_exit);

/* The source is MIT-licensed (see SPDX header), but MODULE_LICENSE("GPL")
 * is required to resolve EXPORT_SYMBOL_GPL symbols (kretprobes, etc.)
 * at module load time. */
MODULE_LICENSE("GPL");
MODULE_AUTHOR("soranerai");
MODULE_DESCRIPTION("Hide VPN interfaces from selected apps at kernel level");
