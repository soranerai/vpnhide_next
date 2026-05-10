#ifndef _VPNHIDE_H
#define _VPNHIDE_H

#include <linux/types.h>

#define MAX_TARGET_UIDS 512
#define MAX_PORT_RULES_PER_UID 16

/* Protocol types for port hiding */
#define VH_PROTO_TCP  0
#define VH_PROTO_UDP  1
#define VH_PROTO_BOTH 2

struct vpnhide_port_rule {
	unsigned short start_port;
	unsigned short end_port;
	unsigned char protocol; /* VH_PROTO_* */
};

struct vpnhide_uid_port_rules {
	uid_t uid;
	int rule_count;
	struct vpnhide_port_rule rules[MAX_PORT_RULES_PER_UID];
};

struct vpnhide_port_ioctl_data {
	int count; /* Number of UIDs in targets array */
	struct vpnhide_uid_port_rules targets[MAX_TARGET_UIDS];
};

struct vpnhide_ioctl_data {
	int count;
	uid_t uids[MAX_TARGET_UIDS];
};

#define VH_IOCTL_MAGIC 0x56

#define VH_SET_TARGETS        _IOW(VH_IOCTL_MAGIC, 0x01, struct vpnhide_ioctl_data)
#define VH_SET_DIRECT_TARGETS _IOW(VH_IOCTL_MAGIC, 0x02, struct vpnhide_ioctl_data)
#define VH_SET_DEBUG          _IOW(VH_IOCTL_MAGIC, 0x03, int)
#define VH_SET_PHYS_IFINDEX   _IOW(VH_IOCTL_MAGIC, 0x04, int)
#define VH_SET_PORT_TARGETS   _IOW(VH_IOCTL_MAGIC, 0x05, struct vpnhide_ioctl_data)
#define VH_SET_PORT_RULES     _IO(VH_IOCTL_MAGIC, 0x06)

#endif /* _VPNHIDE_H */
