// SPDX-License-Identifier: MIT
/*
 * vpnhide_kpm — APatch KPM module for hiding VPN interfaces.
 * Optimized for Linux Kernel 4.14.
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

struct seq_file;
struct file;
struct inode;
struct net;

KPM_NAME("vpnhide");
KPM_VERSION("1.4.7");
KPM_LICENSE("MIT");
KPM_AUTHOR("soranerai");
KPM_DESCRIPTION("Hide VPN interfaces (4.14 Optimized)");

#define MODNAME "vpnhide"
#define MAX_TARGET_UIDS 64
#define IFNAMSIZ 16

/* Dynamic offsets for sk_buff (depends on CONFIG_NF_CONNTRACK) */
static int skb_len_off = 112;
static int skb_cb_off = 40;

/* Minimal Mocks for 4.14 */
struct net_device {
    char name[IFNAMSIZ];
};

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

struct file_operations {
    void *owner;
    loff_t (*llseek) (struct file *, loff_t, int);
    ssize_t (*read) (struct file *, char __user *, size_t, loff_t *);
    ssize_t (*write) (struct file *, const char __user *, size_t, loff_t *);
    void *read_iter;
    void *write_iter;
    void *iopoll;
    void *iterate;
    void *iterate_shared;
    void *poll;
    void *unlocked_ioctl;
    void *compat_ioctl;
    void *mmap;
    unsigned long mmap_supported_flags;
    int (*open) (struct inode *, struct file *);
    void *flush;
    int (*release) (struct inode *, struct file *);
};

/* Seq file mock to access buffer and count */
struct seq_file_mock {
    char *buf;
    size_t size;
    size_t from;
    size_t count;
};

/* Internal State */
static uint32_t target_uids[MAX_TARGET_UIDS];
static int num_targets = 0;

/* Symbols resolved at runtime */
static void *(*_proc_create)(const char *name, uint16_t mode, void *parent, void *ops, void *data) = 0;
static void (*_remove_proc_entry)(const char *name, void *parent) = 0;
static int (*_single_open)(struct file *file, int (*show)(struct seq_file *, void *), void *data) = 0;
static int (*_single_release)(struct inode *inode, struct file *file) = 0;
static ssize_t (*_seq_read)(struct file *file, char __user *buf, size_t size, loff_t *ppos) = 0;
static loff_t (*_seq_lseek)(struct file *file, loff_t offset, int whence) = 0;
static void (*_seq_printf)(struct seq_file *m, const char *f, ...) = 0;
static unsigned long (*_copy_from_user)(void *to, const void __user *from, unsigned long n) = 0;
static void (*_skb_trim)(void *skb, unsigned int len) = 0;
static void *(*_memmove)(void *dest, const void *src, size_t n) = 0;

extern unsigned long (*kallsyms_lookup_name)(const char *name);
extern uid_t current_uid(void);

/* UID Check - Lockless approach */
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
            if (compat_copy_to_user(&usr_ifr[dst], &tmp, sizeof(tmp))) return FILTER_IFCONF_COPY_FAULT;
        }
        dst++;
    }
    if (dst == n) return FILTER_IFCONF_NO_CHANGE;
    *out_len = dst * (int)sizeof(struct ifreq);
    return FILTER_IFCONF_CHANGED;
}

/* Hooks */

/* dev_ioctl(struct net *net, unsigned int cmd, void __user *arg) - 3 args in 4.14 */
static void dev_ioctl_after(hook_fargs3_t *fargs, void *udata) {
    if (fargs->ret != 0 || !is_target_uid()) return;
    
    unsigned int cmd = (unsigned int)fargs->arg1;
    void __user *arg = (void __user *)fargs->arg2;
    if (!arg) return;

    /* Check only name-based getters */
    if (cmd == 0x8913 || cmd == 0x8915 || cmd == 0x8921 || cmd == 0x8927) {
        char name[IFNAMSIZ];
        if (_copy_from_user && _copy_from_user(name, arg, IFNAMSIZ) == 0) {
            name[IFNAMSIZ-1] = '\0';
            if (is_vpn_iface_safe(name)) fargs->ret = -19; /* -ENODEV */
        }
    }
}

static void sock_ioctl_after(hook_fargs3_t *fargs, void *udata) {
    unsigned int cmd = (unsigned int)fargs->arg1;
    unsigned long arg = (unsigned long)fargs->arg2;
    if ((int)fargs->ret < 0 || cmd != 0x8912 || !arg || !is_target_uid()) return;
   
    struct ifconf ifc;
    if (_copy_from_user && _copy_from_user(&ifc, (void __user *)arg, sizeof(ifc)) == 0) {
        if (ifc.ifc_ifcu.ifc_req && ifc.ifc_len > 0) {
            int old_len = ifc.ifc_len;
            enum filter_ifconf_result res = filter_ifconf_buf(ifc.ifc_ifcu.ifc_req, old_len / (int)sizeof(struct ifreq), &ifc.ifc_len);
            if (res == FILTER_IFCONF_CHANGED) {
                compat_copy_to_user((void __user *)arg, &ifc, sizeof(ifc));
            }
        }
    }
}

/* Netlink Filtering Helpers using skb->cb to pass data between before/after */
static void skb_save_len(void *skb) {
    if (!skb) return;
    unsigned int *len_ptr = (unsigned int *)((char *)skb + skb_len_off);
    unsigned int *cb_ptr = (unsigned int *)((char *)skb + skb_cb_off + 40);
    *cb_ptr = *len_ptr;
}

static void skb_restore_len(void *skb) {
    if (!skb || !_skb_trim) return;
    unsigned int *cb_ptr = (unsigned int *)((char *)skb + skb_cb_off + 40);
    _skb_trim(skb, *cb_ptr);
}

/* rtnl_fill_ifinfo has 9 arguments in 4.14 */
static void rtnl_fill_ifinfo_before(hook_fargs9_t *fargs, void *udata) {
    if (is_target_uid()) skb_save_len((void *)fargs->arg0);
}

static void rtnl_fill_ifinfo_after(hook_fargs9_t *fargs, void *udata) {
    if (fargs->ret >= 0 && is_target_uid()) {
        struct net_device *dev = (struct net_device *)fargs->arg1;
        if (dev && is_vpn_iface_safe(dev->name)) {
            skb_restore_len((void *)fargs->arg0);
            fargs->ret = 0; 
        }
    }
}

/* inet_fill_ifaddr has 6 arguments in 4.14 */
static void inet_fill_before(hook_fargs6_t *fargs, void *udata) {
    if (is_target_uid()) skb_save_len((void *)fargs->arg0);
}

static void inet_fill_after(hook_fargs6_t *fargs, void *udata) {
    if (fargs->ret >= 0 && is_target_uid()) {
        void *ifa = (void *)fargs->arg1;
        if (ifa) {
            /* struct in_ifaddr offset for ifa_dev is 24 on 4.14/64-bit */
            void *idev = *(void **)((char *)ifa + 24);
            if (idev && *(void **)idev) {
                struct net_device *dev = *(struct net_device **)idev;
                if (dev && is_vpn_iface_safe(dev->name)) {
                    skb_restore_len((void *)fargs->arg0);
                    fargs->ret = 0;
                }
            }
        }
    }
}

/* inet6_fill_ifaddr has 6 arguments in 4.14 */
static void inet6_fill_before(hook_fargs6_t *fargs, void *udata) {
    if (is_target_uid()) skb_save_len((void *)fargs->arg0);
}

static void inet6_fill_after(hook_fargs6_t *fargs, void *udata) {
    if (fargs->ret >= 0 && is_target_uid()) {
        void *ifa = (void *)fargs->arg1;
        if (ifa) {
            /* struct inet6_ifaddr offset for idev is 168 on 4.14/64-bit (calculated) */
            void *idev = *(void **)((char *)ifa + 168);
            if (idev && *(void **)idev) {
                struct net_device *dev = *(struct net_device **)idev;
                if (dev && is_vpn_iface_safe(dev->name)) {
                    skb_restore_len((void *)fargs->arg0);
                    fargs->ret = 0;
                }
            }
        }
    }
}

/* fib_route_seq_show(struct seq_file *seq, void *v) - 2 args */
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

/* Procfs */
static ssize_t targets_write(struct file *file, const char __user *ubuf, size_t count, loff_t *ppos) {
    char buf[512];
    {
        volatile char *vbuf = buf;
        for (int i = 0; i < 512; i++) vbuf[i] = 0;
    }
    
    uint32_t new_uids[MAX_TARGET_UIDS];
    int new_count = 0;
   
    if (count > sizeof(buf) - 1) count = sizeof(buf) - 1;

    long copied = compat_strncpy_from_user(buf, ubuf, count);
    if (copied < 0) return -14;
    buf[count] = '\0';
   
    char *line = buf;
    char *next;

    for (; line && *line && new_count < MAX_TARGET_UIDS; line = next) {
        next = line;
        while (*next && *next != '\n') next++;
        if (*next == '\n') {
            *next = '\0';
            next++;
        } else {
            next = NULL;
        }

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

    logki(MODNAME ": loaded %d target UIDs\n", new_count);
    return count;
}

static int targets_show(struct seq_file *m, void *v) {
    int n = num_targets;
    asm volatile("dmb ishld" ::: "memory");
    for (int i = 0; i < n; i++) {
        if (_seq_printf) _seq_printf(m, "%u\n", target_uids[i]);
    }
    return 0;
}

static int targets_open(struct inode *inode, struct file *file) {
    if (_single_open) return _single_open(file, targets_show, NULL);
    return -19;
}

static struct file_operations targets_fops = {
    .open = targets_open,
    .write = targets_write,
};

/* Module Lifecycle */
static long vpnhide_kpm_init(const char *args, const char *event, void *__user reserved) {
    logki(MODNAME ": module initializing (v1.4.7 optimized for 4.14)\n");
   
    _proc_create = (void*)kallsyms_lookup_name("proc_create_data");
    _remove_proc_entry = (void*)kallsyms_lookup_name("remove_proc_entry");
    _single_open = (void*)kallsyms_lookup_name("single_open");
    _single_release = (void*)kallsyms_lookup_name("single_release");
    _seq_read = (void*)kallsyms_lookup_name("seq_read");
    _seq_lseek = (void*)kallsyms_lookup_name("seq_lseek");
    _seq_printf = (void*)kallsyms_lookup_name("seq_printf");
    _memmove = (void*)kallsyms_lookup_name("memmove");
    
    _copy_from_user = (void*)kallsyms_lookup_name("__arch_copy_from_user");
    if (!_copy_from_user) _copy_from_user = (void*)kallsyms_lookup_name("raw_copy_from_user");
    if (!_copy_from_user) _copy_from_user = (void*)kallsyms_lookup_name("_copy_from_user");
    if (!_copy_from_user) _copy_from_user = (void*)kallsyms_lookup_name("copy_from_user");
    
    _skb_trim = (void*)kallsyms_lookup_name("__skb_trim");
    if (!_skb_trim) _skb_trim = (void*)kallsyms_lookup_name("skb_trim");

    /* Detect sk_buff layout */
    if (kallsyms_lookup_name("nf_conntrack_destroy") || kallsyms_lookup_name("_nfct")) {
        skb_len_off = 112;
    } else {
        skb_len_off = 104;
    }

    targets_fops.read = _seq_read;
    targets_fops.llseek = _seq_lseek;
    targets_fops.release = _single_release;

    if (_proc_create && _seq_read && _seq_lseek && _single_release && _single_open) {
        _proc_create("vpnhide_targets", 0666, NULL, &targets_fops, NULL);
    }

    unsigned long dev_ioctl_addr = kallsyms_lookup_name("dev_ioctl");
    unsigned long sock_ioctl_addr = kallsyms_lookup_name("sock_ioctl");
    unsigned long rtnl_addr = kallsyms_lookup_name("rtnl_fill_ifinfo");
    unsigned long inet_addr = kallsyms_lookup_name("inet_fill_ifaddr");
    unsigned long inet6_addr = kallsyms_lookup_name("inet6_fill_ifaddr");
    unsigned long fib_route_addr = kallsyms_lookup_name("fib_route_seq_show");

    if (dev_ioctl_addr) hook_wrap((void*)dev_ioctl_addr, 3, 0, dev_ioctl_after, 0);
    if (sock_ioctl_addr) hook_wrap((void*)sock_ioctl_addr, 3, 0, sock_ioctl_after, 0);
    
    if (rtnl_addr) {
        hook_wrap((void*)rtnl_addr, 9, rtnl_fill_ifinfo_before, rtnl_fill_ifinfo_after, 0);
    }
    if (inet_addr) {
        hook_wrap((void*)inet_addr, 6, inet_fill_before, inet_fill_after, 0);
    }
    if (inet6_addr) {
        hook_wrap((void*)inet6_addr, 6, inet6_fill_before, inet6_fill_after, 0);
    }
    if (fib_route_addr) {
        hook_wrap((void*)fib_route_addr, 2, 0, fib_route_after, 0);
    }

    return 0;
}

static long vpnhide_kpm_exit(void *__user reserved) {
    if (_remove_proc_entry) _remove_proc_entry("vpnhide_targets", 0);
    return 0;
}

KPM_INIT(vpnhide_kpm_init);
KPM_EXIT(vpnhide_kpm_exit);
KPM_CTL0(0);
