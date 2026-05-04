// SPDX-License-Identifier: MIT
/*
 * vpnhide_kpm — APatch KPM module for hiding VPN interfaces.
 * Optimized for GKI 6.1.
 */

#pragma GCC visibility push(hidden)
#include <ktypes.h>
#include <kpmodule.h>
#include <hook.h>
#include <kputils.h>
#include <log.h>
#include <symbol.h>
#pragma GCC visibility pop

#include "generated/iface_lists.h"

KPM_NAME("vpnhide");
KPM_VERSION("1.5.3");
KPM_LICENSE("MIT");
KPM_AUTHOR("soranerai");
KPM_DESCRIPTION("Hide VPN interfaces (GKI 6.1 Safe)");

#define MODNAME "vpnhide"
#define MAX_TARGET_UIDS 64
#define IFNAMSIZ 16

struct file;
struct inode;
struct seq_file_mock;

struct net_device {
    char name[IFNAMSIZ];
};


struct proc_ops_mock {
    unsigned int   proc_flags;
    int            (*proc_open)(struct inode *, struct file *);
    ssize_t        (*proc_read)(struct file *, char __user *, size_t, loff_t *);
    void           *proc_read_iter;
    ssize_t        (*proc_write)(struct file *, const char __user *, size_t, loff_t *);
    loff_t         (*proc_lseek)(struct file *, loff_t, int);
    int            (*proc_release)(struct inode *, struct file *);
    void           *proc_poll;
    void           *proc_ioctl;
    void           *proc_compat_ioctl;
    void           *proc_mmap;
    void           *proc_get_unmapped_area;
};

/* Seq file */
struct seq_file_mock {
    char *buf;
    size_t size;
    size_t from;
    size_t count;
};

/* Network ifaddrs */
struct in_device_mock {
    struct net_device *dev;
};

struct in_ifaddr_mock {
    struct hlist_node hash;          /* 16 bytes */
    struct in_ifaddr_mock *ifa_next; /* 8 bytes */
    struct in_device_mock *ifa_dev;  /* 8 bytes (Offset 24) */
};

struct inet6_dev_mock {
    struct net_device *dev;
};

struct inet6_ifaddr_mock {
    char ifa_address[16];
    char ifa_local[16];
    char ifa_peer[16];
    int ifa_prefixlen;
    uint32_t ifa_flags;
    uint32_t rt_priority;        /* New in 6.1? */
    struct hlist_node addr_lst;  /* 16 bytes */
    struct list_head if_list;    /* 16 bytes */
    struct list_head tmp_list;   /* 16 bytes */
    char _pad_dad_work[104];     /* Padding for dad_work (approx 104 bytes in 6.1) */
    struct inet6_dev_mock *idev; /* Exact Offset: 216 (confirmed for GKI 6.1) */
};

/* IOCTL Structs */
struct ifreq {
    char ifr_name[IFNAMSIZ];
    union {
        char ifr_hwaddr[IFNAMSIZ];
        int ifr_ifindex;
        unsigned short ifr_flags;
        int ifr_mtu;
        char _padding[24];
    } ifr_ifru;
};

struct ifconf {
    int ifc_len;
    union {
        char __user *ifc_buf;
        struct ifreq __user *ifc_req;
    } ifc_ifcu;
};

/* =========================================================================
 * STATE & RESOLVED SYMBOLS
 * ========================================================================= */

static uint32_t target_uids[MAX_TARGET_UIDS];
static int num_targets = 0;
static bool debug_enabled = false;

static int skb_len_off = 112;

#define VPNHIDE_MAX_CPUS 16
static volatile unsigned int g_saved_len[VPNHIDE_MAX_CPUS];
static volatile void       *g_saved_skb[VPNHIDE_MAX_CPUS];

static inline unsigned int vpnhide_cpu_id(void) {
    unsigned long mpidr;
    asm volatile("mrs %0, mpidr_el1" : "=r"(mpidr));
    return (unsigned int)(mpidr & 0xFF) % VPNHIDE_MAX_CPUS;
}

#define vpnhide_dbg(fmt, ...) \
    if (debug_enabled) logki(MODNAME ": " fmt, ##__VA_ARGS__)

static void *(*_proc_create)(const char *, uint16_t, void *, void *, void *) = 0;
static void (*_remove_proc_entry)(const char *, void *) = 0;
static int (*_single_open)(struct file *, int (*)(struct seq_file_mock *, void *), void *) = 0;
static int (*_single_release)(struct inode *, struct file *) = 0;
static ssize_t (*_seq_read)(struct file *, char __user *, size_t, loff_t *) = 0;
static loff_t (*_seq_lseek)(struct file *, loff_t, int) = 0;
static void (*_seq_printf)(struct seq_file_mock *, const char *, ...) = 0;
static unsigned long (*_copy_from_user)(void *, const void __user *, unsigned long) = 0;
static unsigned long (*_copy_to_user)(void __user *, const void *, unsigned long) = 0;
static char *(*_strchr)(const char *, int) = 0;
static void *(*_memmove)(void *, const void *, size_t) = 0;
static void (*_skb_trim)(void *, unsigned int) = 0;



static void skb_save_len(void *skb) {
    if (!skb) return;
    unsigned int cpu = vpnhide_cpu_id();
    unsigned int *len_ptr = (unsigned int *)((char *)skb + skb_len_off);
    g_saved_skb[cpu] = skb;
    g_saved_len[cpu] = *len_ptr;
}

static void skb_restore_len(void *skb) {
    if (!skb || !_skb_trim) return;
    unsigned int cpu = vpnhide_cpu_id();
    if (g_saved_skb[cpu] != skb) return;
    _skb_trim(skb, g_saved_len[cpu]);
    g_saved_skb[cpu] = 0;
}

extern unsigned long (*kallsyms_lookup_name)(const char *name);
extern uid_t current_uid(void);

/* =========================================================================
 * CORE LOGIC
 * ========================================================================= */

static int is_target_uid(void) {
    int n = num_targets;
    asm volatile("dmb ishld" ::: "memory");
    if (n <= 0) return 0;

    uid_t uid = current_uid();
    for (int i = 0; i < n; i++) {
        if (target_uids[i] == uid) return 1;
    }
    return 0;
}

static int is_vpn_iface_safe(const char *name) {
    char buf[IFNAMSIZ + 1];
    int i;
    if (!name) return 0;
    for (i = 0; i < IFNAMSIZ; i++) {
        buf[i] = name[i];
        if (buf[i] == '\0') break;
    }
    buf[i] = '\0';
    return vpnhide_iface_is_vpn(buf);
}

/*
 * inet_sk_diag_fill(sk, icsk, skb, cb, req, nlmsg_flags, net_admin) — 7 args in 6.1.
 */
static void inet_diag_fill_before(hook_fargs7_t *fargs, void *udata) {
    if (is_target_uid()) skb_save_len((void *)fargs->arg2);
}

static void inet_diag_fill_after(hook_fargs7_t *fargs, void *udata) {
    if (fargs->ret >= 0 && is_target_uid()) {
        skb_restore_len((void *)fargs->arg2);
        fargs->ret = 0;
    }
}

/*
 * fib_dump_info(skb, portid, seq, event, fri, flags) — 6 args in 6.1.
 * arg0=skb, arg4=fri (struct fib_rt_info *).
 */
static void fib_dump_before(hook_fargs6_t *fargs, void *udata) {
    if (is_target_uid()) skb_save_len((void *)fargs->arg0);
}

static void fib_dump_after(hook_fargs6_t *fargs, void *udata) {
    if (fargs->ret >= 0 && is_target_uid()) {
        skb_restore_len((void *)fargs->arg0);
        fargs->ret = 0;
    }
}

/*
 * rt6_fill_node(net, skb, rt, dst, dest, src, iif, type, portid, seq, flags) — 11 args in 6.1.
 * arg1=skb, arg2=rt (struct fib6_info *).
 */
static void rt6_fill_before(hook_fargs11_t *fargs, void *udata) {
    if (is_target_uid()) skb_save_len((void *)fargs->arg1);
}

static void rt6_fill_after(hook_fargs11_t *fargs, void *udata) {
    if (fargs->ret >= 0 && is_target_uid()) {
        skb_restore_len((void *)fargs->arg1);
        fargs->ret = 0;
    }
}

enum filter_ifconf_result {
    FILTER_IFCONF_NO_CHANGE,
    FILTER_IFCONF_CHANGED,
    FILTER_IFCONF_COPY_FAULT,
};

static enum filter_ifconf_result filter_ifconf_buf(struct ifreq __user *usr_ifr, int n, int *out_len) {
    struct ifreq tmp;
    int i, dst = 0;
    for (i = 0; i < n; i++) {
        if (_copy_from_user && _copy_from_user(&tmp, &usr_ifr[i], sizeof(tmp))) return FILTER_IFCONF_COPY_FAULT;
        tmp.ifr_name[IFNAMSIZ-1] = '\0';
        if (is_vpn_iface_safe(tmp.ifr_name)) continue;
        if (dst != i) {
            if (_copy_to_user && _copy_to_user(&usr_ifr[dst], &tmp, sizeof(tmp))) return FILTER_IFCONF_COPY_FAULT;
        }
        dst++;
    }
    if (dst == n) return FILTER_IFCONF_NO_CHANGE;
    *out_len = dst * (int)sizeof(struct ifreq);
    return FILTER_IFCONF_CHANGED;
}

/* =========================================================================
 * HOOKS
 * ========================================================================= */

static void dev_ioctl_after(hook_fargs5_t *fargs, void *udata) {
    struct ifreq *ifr = (struct ifreq *)fargs->arg2;
    if (fargs->ret != 0 || !fargs->arg0 || !ifr || !is_target_uid()) return;
    if (is_vpn_iface_safe(ifr->ifr_name)) fargs->ret = -19; /* -ENODEV */
}

static void sock_ioctl_after(hook_fargs3_t *fargs, void *udata) {
    unsigned int cmd = (unsigned int)fargs->arg1;
    unsigned long arg = (unsigned long)fargs->arg2;
    if ((int)fargs->ret < 0 || cmd != 0x8912 /* SIOCGIFCONF */ || !arg || !is_target_uid()) return;
   
    struct ifconf ifc;
    if (_copy_from_user && _copy_from_user(&ifc, (void __user *)arg, sizeof(ifc)) == 0) {
        if (ifc.ifc_ifcu.ifc_req && ifc.ifc_len > 0) {
            int old_len = ifc.ifc_len;
            enum filter_ifconf_result res = filter_ifconf_buf(ifc.ifc_ifcu.ifc_req, old_len / (int)sizeof(struct ifreq), &ifc.ifc_len);
            if (res == FILTER_IFCONF_CHANGED) {
                if (_copy_to_user) _copy_to_user((void __user *)arg, &ifc, sizeof(ifc));
            }
        }
    }
}

/*
 * rtnl_fill_ifinfo(skb, dev, src_net, ...) — GKI 6.1 has 14 arguments.
 * arg0=skb, arg1=dev.
 */
static void rtnl_fill_ifinfo_before(hook_fargs12_t *fargs, void *udata) {
    if (is_target_uid()) skb_save_len((void *)fargs->arg0);
}

static void rtnl_fill_ifinfo_after(hook_fargs12_t *fargs, void *udata) {
    struct net_device *dev = (struct net_device *)fargs->arg1;
    if (dev && is_vpn_iface_safe(dev->name)) {
        if (fargs->ret >= 0 && is_target_uid()) {
            skb_restore_len((void *)fargs->arg0);
            fargs->ret = 0;
        }
    }
}

/*
 * inet_fill_ifaddr(skb, ifa, args) — GKI 6.1 has only 3 arguments.
 */
static void inet_fill_before(hook_fargs3_t *fargs, void *udata) {
    if (is_target_uid()) skb_save_len((void *)fargs->arg0);
}

static void inet_fill_after(hook_fargs3_t *fargs, void *udata) {
    if (fargs->ret >= 0 && is_target_uid()) {
        struct in_ifaddr_mock *ifa = (struct in_ifaddr_mock *)fargs->arg1;
        if (ifa && ifa->ifa_dev) {
            struct net_device *dev = ifa->ifa_dev->dev;
            if (dev && is_vpn_iface_safe(dev->name)) {
                skb_restore_len((void *)fargs->arg0);
                fargs->ret = 0;
            }
        }
    }
}

static void inet6_fill_before(hook_fargs3_t *fargs, void *udata) {
    if (is_target_uid()) skb_save_len((void *)fargs->arg0);
}

static void inet6_fill_after(hook_fargs3_t *fargs, void *udata) {
    if (fargs->ret >= 0 && is_target_uid()) {
        struct inet6_ifaddr_mock *ifa = (struct inet6_ifaddr_mock *)fargs->arg1;
        if (ifa && ifa->idev) {
            struct net_device *dev = ifa->idev->dev;
            if (dev && is_vpn_iface_safe(dev->name)) {
                skb_restore_len((void *)fargs->arg0);
                fargs->ret = 0;
            }
        }
    }
}

static void fib_route_after(hook_fargs2_t *fargs, void *udata) {
    struct seq_file_mock *m = (struct seq_file_mock *)fargs->arg0;
    if (!m || !m->buf || m->count == 0 || !is_target_uid()) return;

    char *src = m->buf;
    char *dst = m->buf;
    char *end = m->buf + m->count;
    char ifname[IFNAMSIZ];

    while (src < end) {
        char *nl = src;
        while (nl < end && *nl != '\n') nl++;
        char *line_end = (nl < end) ? nl + 1 : end;
        size_t line_len = line_end - src;

        int j = 0;
        while (j < IFNAMSIZ - 1 && j < (int)line_len && src[j] != '\t' && src[j] != '\n') {
            ifname[j] = src[j];
            j++;
        }
        ifname[j] = '\0';

        if (is_vpn_iface_safe(ifname)) {
            vpnhide_dbg("hiding route for %s\n", ifname);
            src = line_end;
            continue;
        }

        if (dst != src && _memmove) {
            _memmove(dst, src, line_len);
        }
        dst += line_len;
        src = line_end;
    }
    m->count = dst - m->buf;
}

/* =========================================================================
 * PROCFS
 * ========================================================================= */

static ssize_t targets_write(struct file *file, const char __user *ubuf, size_t count, loff_t *ppos) {
    char buf[1024];
    char *line, *next;
    uint32_t new_uids[MAX_TARGET_UIDS];
    int new_count = 0;

    if (count > sizeof(buf) - 1) count = sizeof(buf) - 1;
    if (count == 0) return 0;

    if (_copy_from_user && _copy_from_user(buf, ubuf, count)) return -14;
    buf[count] = '\0';

    for (line = buf; line && *line && new_count < MAX_TARGET_UIDS; line = next) {
        next = _strchr ? _strchr(line, '\n') : 0;
        if (next) *next++ = '\0';

        while (*line == ' ' || *line == '\t') line++;
        if (!*line || *line == '#') continue;

        unsigned long uid = 0;
        char *p = line;
        int valid = 0;
        while (*p >= '0' && *p <= '9') {
            uid = uid * 10 + (*p - '0');
            valid = 1;
            p++;
        }
        if (valid) new_uids[new_count++] = (uint32_t)uid;
    }
    
    num_targets = 0;
    asm volatile("dmb ishst" ::: "memory");
    for (int i = 0; i < new_count; i++) target_uids[i] = new_uids[i];
    asm volatile("dmb ishst" ::: "memory");
    num_targets = new_count;

    vpnhide_dbg("loaded %d target UIDs\n", new_count);
    return count;
}

static ssize_t debug_write(struct file *file, const char __user *ubuf, size_t count, loff_t *ppos) {
    char c;
    if (count == 0) return 0;
    if (_copy_from_user && _copy_from_user(&c, ubuf, 1)) return -14;
    debug_enabled = (c == '1' || c == 'Y' || c == 'y');
    logki(MODNAME ": debug %s\n", debug_enabled ? "enabled" : "disabled");
    return count;
}

static int debug_show(struct seq_file_mock *m, void *v) {
    if (_seq_printf) _seq_printf(m, "%d\n", debug_enabled ? 1 : 0);
    return 0;
}

static int debug_open(struct inode *inode, struct file *file) {
    if (_single_open) return _single_open(file, (void *)debug_show, NULL);
    return -19;
}

static int targets_show(struct seq_file_mock *m, void *v) {
    int n = num_targets;
    asm volatile("dmb ishld" ::: "memory");
    for (int i = 0; i < n; i++) {
        if (_seq_printf) _seq_printf(m, "%u\n", target_uids[i]);
    }
    return 0;
}

static int targets_open(struct inode *inode, struct file *file) {
    if (_single_open) return _single_open(file, (void *)targets_show, NULL);
    return -19;
}

static struct proc_ops_mock targets_proc_ops = {
    .proc_open = targets_open,
    .proc_write = targets_write,
};

static struct proc_ops_mock debug_proc_ops = {
    .proc_open = debug_open,
    .proc_write = debug_write,
};

/* =========================================================================
 * MODULE INIT / EXIT
 * ========================================================================= */

static long vpnhide_kpm_init(const char *args, const char *event, void *__user reserved) {
    logki(MODNAME ": module initializing (GKI 6.1 Safe)\n");
   
    _proc_create = (void*)kallsyms_lookup_name("proc_create_data");
    _remove_proc_entry = (void*)kallsyms_lookup_name("remove_proc_entry");
    _single_open = (void*)kallsyms_lookup_name("single_open");
    _single_release = (void*)kallsyms_lookup_name("single_release");
    _seq_read = (void*)kallsyms_lookup_name("seq_read");
    _seq_lseek = (void*)kallsyms_lookup_name("seq_lseek");
    _seq_printf = (void*)kallsyms_lookup_name("seq_printf");
    _strchr = (void*)kallsyms_lookup_name("strchr");
    _memmove = (void*)kallsyms_lookup_name("memmove");
    
    _copy_from_user = (void*)kallsyms_lookup_name("__arch_copy_from_user");
    if (!_copy_from_user) _copy_from_user = (void*)kallsyms_lookup_name("raw_copy_from_user");
    if (!_copy_from_user) _copy_from_user = (void*)kallsyms_lookup_name("_copy_from_user");
    if (!_copy_from_user) _copy_from_user = (void*)kallsyms_lookup_name("copy_from_user");

    _copy_to_user = (void*)kallsyms_lookup_name("__arch_copy_to_user");
    if (!_copy_to_user) _copy_to_user = (void*)kallsyms_lookup_name("raw_copy_to_user");
    if (!_copy_to_user) _copy_to_user = (void*)kallsyms_lookup_name("_copy_to_user");
    if (!_copy_to_user) _copy_to_user = (void*)kallsyms_lookup_name("copy_to_user");
    
    _skb_trim = (void*)kallsyms_lookup_name("__skb_trim");
    if (!_skb_trim) _skb_trim = (void*)kallsyms_lookup_name("skb_trim");

    targets_proc_ops.proc_read = _seq_read;
    targets_proc_ops.proc_lseek = _seq_lseek;
    targets_proc_ops.proc_release = _single_release;

    debug_proc_ops.proc_read = _seq_read;
    debug_proc_ops.proc_lseek = _seq_lseek;
    debug_proc_ops.proc_release = _single_release;

    if (_proc_create && _seq_read && _seq_lseek && _single_release && _single_open) {
        _proc_create("vpnhide_targets", 0666, NULL, &targets_proc_ops, NULL);
        _proc_create("vpnhide_debug", 0666, NULL, &debug_proc_ops, NULL);
    }

    unsigned long dev_ioctl_addr = kallsyms_lookup_name("dev_ioctl");
    unsigned long sock_ioctl_addr = kallsyms_lookup_name("sock_ioctl");
    unsigned long rtnl_addr = kallsyms_lookup_name("rtnl_fill_ifinfo");
    unsigned long inet_addr = kallsyms_lookup_name("inet_fill_ifaddr");
    unsigned long inet6_addr = kallsyms_lookup_name("inet6_fill_ifaddr");
    unsigned long diag_fill_addr = kallsyms_lookup_name("inet_sk_diag_fill");
    unsigned long fib_dump_addr = kallsyms_lookup_name("fib_dump_info");
    unsigned long rt6_fill_addr = kallsyms_lookup_name("rt6_fill_node");
    unsigned long fib_route_addr = kallsyms_lookup_name("fib_route_seq_show");

    if (dev_ioctl_addr) hook_wrap((void*)dev_ioctl_addr, 5, 0, dev_ioctl_after, 0);
    if (sock_ioctl_addr) hook_wrap((void*)sock_ioctl_addr, 3, 0, sock_ioctl_after, 0);
    
    if (rtnl_addr)
        hook_wrap((void*)rtnl_addr, 14, rtnl_fill_ifinfo_before, rtnl_fill_ifinfo_after, 0);
    if (inet_addr)
        hook_wrap((void*)inet_addr, 3, inet_fill_before, inet_fill_after, 0);
    if (inet6_addr)
        hook_wrap((void*)inet6_addr, 3, inet6_fill_before, inet6_fill_after, 0);
    if (diag_fill_addr)
        hook_wrap((void*)diag_fill_addr, 7, inet_diag_fill_before, inet_diag_fill_after, 0);
    if (fib_dump_addr)
        hook_wrap((void*)fib_dump_addr, 6, fib_dump_before, fib_dump_after, 0);
    if (rt6_fill_addr)
        hook_wrap((void*)rt6_fill_addr, 11, rt6_fill_before, rt6_fill_after, 0);
    if (fib_route_addr) hook_wrap((void*)fib_route_addr, 2, 0, fib_route_after, 0);

    return 0;
}

static long vpnhide_kpm_exit(void *__user reserved) {
    if (_remove_proc_entry) {
        _remove_proc_entry("vpnhide_targets", 0);
        _remove_proc_entry("vpnhide_debug", 0);
    }
    return 0;
}

KPM_INIT(vpnhide_kpm_init);
KPM_EXIT(vpnhide_kpm_exit);
KPM_CTL0(0);