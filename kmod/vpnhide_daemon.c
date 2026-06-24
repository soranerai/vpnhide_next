#include <stdio.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <arpa/inet.h>
#include <linux/types.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdbool.h>
#include <poll.h>
#include <dirent.h>

#include "include/vpnhide.h"

static bool is_interface_operstate_up(const char *ifname)
{
	char path[256];
	char buf[32];
	int fd;
	ssize_t len;

	snprintf(path, sizeof(path), "/sys/class/net/%s/operstate", ifname);
	fd = open(path, O_RDONLY);
	if (fd < 0)
		return false;

	len = read(fd, buf, sizeof(buf) - 1);
	close(fd);

	if (len <= 0)
		return false;

	buf[len] = '\0';
	while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r' ||
			   buf[len - 1] == ' ')) {
		buf[--len] = '\0';
	}

	return strcmp(buf, "up") == 0 || strcmp(buf, "unknown") == 0;
}

#include <time.h>

struct gateway_list {
	char names[32][IFNAMSIZ];
	int count;
};

static void load_gateway_list(struct gateway_list *list)
{
	list->count = 0;

	FILE *f = fopen("/proc/net/route", "r");
	if (f) {
		char line[256];
		if (fgets(line, sizeof(line), f)) { // Skip header
			while (fgets(line, sizeof(line), f)) {
				char iface[32];
				unsigned int dest, gw, flags;
				if (sscanf(line, "%31s %x %x %x", iface, &dest,
					   &gw, &flags) == 4) {
					if (flags & 0x0002) { // RTF_GATEWAY
						bool dup = false;
						for (int i = 0; i < list->count;
						     i++) {
							if (strcmp(list->names[i],
								   iface) ==
							    0) {
								dup = true;
								break;
							}
						}
						if (!dup && list->count < 32) {
							strncpy(list->names
									[list->count],
								iface,
								IFNAMSIZ - 1);
							list->names[list->count]
								   [IFNAMSIZ -
								    1] = '\0';
							list->count++;
						}
					}
				}
			}
		}
		fclose(f);
	}

	f = fopen("/proc/net/ipv6_route", "r");
	if (f) {
		char line[256];
		while (fgets(line, sizeof(line), f)) {
			char dst_ip[33], src_ip[33], gw_ip[33], iface[32];
			unsigned int dst_len, src_len, metric, refcnt, use,
				flags;
			if (sscanf(line,
				   "%32s %x %32s %x %32s %x %x %x %x %31s",
				   dst_ip, &dst_len, src_ip, &src_len, gw_ip,
				   &metric, &refcnt, &use, &flags,
				   iface) == 10) {
				if (flags & 0x0002) { // RTF_GATEWAY
					bool dup = false;
					for (int i = 0; i < list->count; i++) {
						if (strcmp(list->names[i],
							   iface) == 0) {
							dup = true;
							break;
						}
					}
					if (!dup && list->count < 32) {
						strncpy(list->names[list->count],
							iface, IFNAMSIZ - 1);
						list->names[list->count]
							   [IFNAMSIZ - 1] =
							'\0';
						list->count++;
					}
				}
			}
		}
		fclose(f);
	}
}

static bool has_gateway_route(const struct gateway_list *list,
			      const char *ifname)
{
	for (int i = 0; i < list->count; i++) {
		if (strcmp(list->names[i], ifname) == 0)
			return true;
	}
	return false;
}

static bool
daemon_is_vpn_ifname(const char *name,
		     const struct vpnhide_iface_ioctl_data *prefixes)
{
	/* Built-in patterns */
	if (strncmp(name, "tun", 3) == 0 || strncmp(name, "ppp", 3) == 0 ||
	    strncmp(name, "wg", 2) == 0 || strncmp(name, "tap", 3) == 0 ||
	    strncmp(name, "ipsec", 5) == 0 || strncmp(name, "dummy", 5) == 0 ||
	    strncmp(name, "pdp", 3) == 0 || strncmp(name, "p2p", 3) == 0) {
		return true;
	}

	/* Configured prefixes */
	if (prefixes) {
		for (int i = 0; i < prefixes->count; i++) {
			int len = strlen(prefixes->prefixes[i]);
			if (len > 0 && strncasecmp(name, prefixes->prefixes[i],
						   len) == 0) {
				return true;
			}
		}
	}
	return false;
}

static void update_spoof_ip(int fd, const struct gateway_list *gw_list,
			    char *last_ipv4, char *last_ipv6)
{
	struct ifaddrs *ifaddr = NULL;
	struct ifaddrs *ifa = NULL;
	char best_ifname[IFNAMSIZ];
	int best_score = -1;
	char new_ipv4[64];
	char new_ipv6[64];
	struct vpnhide_iface_ioctl_data prefixes;
	struct vpnhide_vpn_ifindexes active_vpns;

	memset(&prefixes, 0, sizeof(prefixes));
	memset(&active_vpns, 0, sizeof(active_vpns));
	ioctl(fd, VH_GET_IFACE_PREFIXES, &prefixes);

	best_ifname[0] = '\0';
	strcpy(new_ipv4, "none");
	strcpy(new_ipv6, "none");

	if (getifaddrs(&ifaddr) == -1) {
		return;
	}

	/* Helper structures to aggregate interface info */
	struct iface_info {
		char name[IFNAMSIZ];
		bool has_ipv4;
		bool has_ipv6;
		bool has_gateway;
		int score;
	} interfaces[32];
	int iface_count = 0;

	for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
		if (ifa->ifa_addr == NULL)
			continue;

		if (!(ifa->ifa_flags & IFF_UP))
			continue;

		if (ifa->ifa_flags & IFF_LOOPBACK)
			continue;

		char *name = ifa->ifa_name;

		if (daemon_is_vpn_ifname(name, &prefixes)) {
			unsigned int vpn_idx = if_nametoindex(name);
			if (vpn_idx > 0) {
				bool dup = false;
				for (int i = 0; i < active_vpns.count; i++) {
					if (active_vpns.ifindexes[i] ==
					    vpn_idx) {
						dup = true;
						break;
					}
				}
				if (!dup &&
				    active_vpns.count < MAX_ACTIVE_VPNS) {
					active_vpns
						.ifindexes[active_vpns.count++] =
						vpn_idx;
				}
			}
			continue;
		}

		if (!is_interface_operstate_up(name))
			continue;

		/* Find or create interface info slot */
		int idx = -1;
		for (int i = 0; i < iface_count; i++) {
			if (strcmp(interfaces[i].name, name) == 0) {
				idx = i;
				break;
			}
		}
		if (idx == -1 && iface_count < 32) {
			idx = iface_count++;
			memset(&interfaces[idx], 0, sizeof(struct iface_info));
			strncpy(interfaces[idx].name, name, IFNAMSIZ - 1);
			interfaces[idx].has_gateway =
				has_gateway_route(gw_list, name);
		}

		if (idx != -1) {
			int family = ifa->ifa_addr->sa_family;
			if (family == AF_INET) {
				interfaces[idx].has_ipv4 = true;
			} else if (family == AF_INET6) {
				struct sockaddr_in6 *sa =
					(struct sockaddr_in6 *)ifa->ifa_addr;
				if (IN6_IS_ADDR_LINKLOCAL(&(sa->sin6_addr)))
					continue;
				interfaces[idx].has_ipv6 = true;
			}
		}
	}

	/* Score all aggregated interfaces */
	for (int i = 0; i < iface_count; i++) {
		struct iface_info *info = &interfaces[i];
		info->score = 1000;

		if (strncmp(info->name, "eth", 3) == 0) {
			info->score = 100000;
		} else if (strncmp(info->name, "wlan", 4) == 0 ||
			   strncmp(info->name, "ap", 2) == 0) {
			info->score = 50000;
		} else if (strncmp(info->name, "rmnet", 5) == 0 ||
			   strncmp(info->name, "ccmni", 5) == 0 ||
			   strncmp(info->name, "epdg", 4) == 0 ||
			   strncmp(info->name, "r_net", 5) == 0 ||
			   strncmp(info->name, "pdp", 3) == 0) {
			info->score = 10000;
		}

		if (info->has_gateway)
			info->score += 20000;

		/* Add priority for dual-stack or having actual IP addresses */
		if (info->has_ipv4)
			info->score += 5000;
		if (info->has_ipv6)
			info->score += 5000;

		if (info->score > best_score) {
			best_score = info->score;
			strncpy(best_ifname, info->name, IFNAMSIZ - 1);
			best_ifname[IFNAMSIZ - 1] = '\0';
		}
	}

	if (best_score > 0) {
		/* Find IPv4 and IPv6 for the best interface */
		for (ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
			if (ifa->ifa_addr == NULL ||
			    strcmp(ifa->ifa_name, best_ifname) != 0)
				continue;

			int family = ifa->ifa_addr->sa_family;
			if (family == AF_INET) {
				struct sockaddr_in *sa =
					(struct sockaddr_in *)ifa->ifa_addr;
				inet_ntop(AF_INET, &(sa->sin_addr), new_ipv4,
					  sizeof(new_ipv4));
			} else if (family == AF_INET6) {
				struct sockaddr_in6 *sa =
					(struct sockaddr_in6 *)ifa->ifa_addr;
				if (IN6_IS_ADDR_LINKLOCAL(&(sa->sin6_addr)))
					continue;
				inet_ntop(AF_INET6, &(sa->sin6_addr), new_ipv6,
					  sizeof(new_ipv6));
			}
		}
	}

	freeifaddrs(ifaddr);

	if (strcmp(new_ipv4, last_ipv4) != 0 ||
	    strcmp(new_ipv6, last_ipv6) != 0) {
		struct vpnhide_spoof_ip spoof;
		memset(&spoof, 0, sizeof(spoof));

		if (strcmp(new_ipv4, "none") != 0) {
			if (inet_pton(AF_INET, new_ipv4, &spoof.ipv4_addr) ==
			    1) {
				spoof.has_ipv4 = 1;
			}
		}
		if (strcmp(new_ipv6, "none") != 0) {
			if (inet_pton(AF_INET6, new_ipv6, spoof.ipv6_addr) ==
			    1) {
				spoof.has_ipv6 = 1;
			}
		}

		if (ioctl(fd, VH_SET_SPOOF_IP, &spoof) == 0) {
			strcpy(last_ipv4, new_ipv4);
			strcpy(last_ipv6, new_ipv6);
		}
	}

	/* Always update cover ifindex so the kernel's BPF stats laundering
	 * uses the correct interface even if the spoof IP hasn't changed. */
	if (best_ifname[0] != '\0') {
		struct vpnhide_cover_iface ci;
		ci.ifindex = if_nametoindex(best_ifname);
		if (ci.ifindex > 0) {
			ioctl(fd, VH_SET_COVER_IFACE, &ci);
			char buf[64];
			int len = snprintf(buf, sizeof(buf), "cover_iface:%s\n", best_ifname);
			if (len > 0) {
				write(fd, buf, len);
			}
		}
	} else {
		write(fd, "cover_iface:none\n", 17);
	}

	/* Send the list of active VPNs to the kernel module */
	ioctl(fd, VH_SET_VPN_IFINDEXES, &active_vpns);
}

static unsigned long long get_time_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (unsigned long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

int main(int argc, char **argv)
{
	int fd, nl_fd;
	struct sockaddr_nl sa;
	char last_ipv4[64];
	char last_ipv6[64];

	setbuf(stdout, NULL);
	setbuf(stderr, NULL);

	strcpy(last_ipv4, "none");
	strcpy(last_ipv6, "none");

	fd = open("/dev/vpnhide_ctrl", O_RDWR);
	if (fd < 0) {
		fprintf(stderr,
			"vpnhide-daemon: failed to open /dev/vpnhide_ctrl: %d (%s)\n",
			errno, strerror(errno));
		return 1;
	}

	nl_fd = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);
	if (nl_fd < 0) {
		close(fd);
		return 1;
	}

	memset(&sa, 0, sizeof(sa));
	sa.nl_family = AF_NETLINK;
	sa.nl_groups = RTMGRP_LINK | RTMGRP_IPV4_IFADDR | RTMGRP_IPV6_IFADDR |
		       RTMGRP_IPV4_ROUTE | RTMGRP_IPV6_ROUTE;
	if (bind(nl_fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
		close(nl_fd);
		close(fd);
		return 1;
	}

	// Initial update
	struct gateway_list gw_list;
	load_gateway_list(&gw_list);
	update_spoof_ip(fd, &gw_list, last_ipv4, last_ipv6);

	unsigned long long next_update_time = 0;
	bool update_pending = false;
	int retry_count = 0;

	while (1) {
		int poll_timeout = -1;
		if (update_pending) {
			unsigned long long now = get_time_ms();
			if (now >= next_update_time) {
				poll_timeout = 0;
			} else {
				poll_timeout = (int)(next_update_time - now);
			}
		}

		struct pollfd pfd;
		pfd.fd = nl_fd;
		pfd.events = POLLIN;

		int ret = poll(&pfd, 1, poll_timeout);
		if (ret < 0) {
			if (errno == EINTR)
				continue;
			sleep(1);
			continue;
		}

		bool trigger_update = false;

		if (ret > 0 && (pfd.revents & POLLIN)) {
			char buf[4096];
			// Consume all pending data on netlink socket to clear the POLLIN state
			while (recv(nl_fd, buf, sizeof(buf), MSG_DONTWAIT) > 0)
				;
			usleep(200000); // 200ms debounce
			trigger_update = true;
		}

		if (update_pending && get_time_ms() >= next_update_time) {
			trigger_update = true;
			update_pending = false;
		}

		if (trigger_update) {
			load_gateway_list(&gw_list);
			update_spoof_ip(fd, &gw_list, last_ipv4, last_ipv6);

			if (ret > 0) {
				// Netlink event occurred, schedule follow-ups
				next_update_time = get_time_ms() + 1000;
				update_pending = true;
				retry_count = 2;
			} else if (retry_count > 0) {
				retry_count--;
				if (retry_count == 1) {
					next_update_time = get_time_ms() + 2000;
					update_pending = true;
				}
			}
		}
	}

	close(nl_fd);
	close(fd);
	return 0;
}
