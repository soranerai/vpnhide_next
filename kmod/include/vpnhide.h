#ifndef _VPNHIDE_H
#define _VPNHIDE_H

#include <linux/types.h>

#define MAX_TARGET_UIDS 512
#define MAX_PORT_RULES_PER_UID 16

/* Protocol types for port hiding */
#define VH_PROTO_TCP 0
#define VH_PROTO_UDP 1
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

#define MAX_IFACE_PREFIXES 32
#define MAX_IFACE_LEN 16

struct vpnhide_iface_ioctl_data {
	int count;
	char prefixes[MAX_IFACE_PREFIXES][MAX_IFACE_LEN];
};

struct vpnhide_spoof_ip {
	__be32 ipv4_addr;       /* IPv4 address in network byte order */
	__u8 ipv6_addr[16];     /* IPv6 address */
	__u8 has_ipv4;
	__u8 has_ipv6;
};

#define VH_SET_TARGETS _IOW(VH_IOCTL_MAGIC, 0x01, struct vpnhide_ioctl_data)
#define VH_SET_DEBUG _IOW(VH_IOCTL_MAGIC, 0x03, int)
#define VH_SET_PORT_TARGETS \
	_IOW(VH_IOCTL_MAGIC, 0x05, struct vpnhide_ioctl_data)
#define VH_SET_PORT_RULES _IO(VH_IOCTL_MAGIC, 0x06)
#define VH_SET_IFACE_PREFIXES \
	_IOW(VH_IOCTL_MAGIC, 0x07, struct vpnhide_iface_ioctl_data)
#define VH_SET_SPOOF_IP \
	_IOW(VH_IOCTL_MAGIC, 0x08, struct vpnhide_spoof_ip)
#define VH_SET_ACTIVE_HOOKS _IOW(VH_IOCTL_MAGIC, 0x09, unsigned int)
#define VH_GET_ACTIVE_HOOKS _IOR(VH_IOCTL_MAGIC, 0x0A, unsigned int)

#endif /* _VPNHIDE_H */
