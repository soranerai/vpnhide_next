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
#include "generated/iface_lists.h"

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

static int test_interface_egress(const char *ifname, int af, char *out_ip, size_t max_len)
{
	int sock = socket(af, SOCK_DGRAM, 0);
	if (sock < 0)
		return 0;

	if (setsockopt(sock, SOL_SOCKET, SO_BINDTODEVICE, ifname, strlen(ifname)) < 0) {
		close(sock);
		return 0;
	}

	if (af == AF_INET) {
		struct sockaddr_in serv;
		memset(&serv, 0, sizeof(serv));
		serv.sin_family = AF_INET;
		serv.sin_addr.s_addr = inet_addr("8.8.8.8");
		serv.sin_port = htons(53);

		if (connect(sock, (const struct sockaddr *)&serv, sizeof(serv)) < 0) {
			close(sock);
			return 0;
		}

		struct sockaddr_in name;
		socklen_t namelen = sizeof(name);
		if (getsockname(sock, (struct sockaddr *)&name, &namelen) == 0) {
			inet_ntop(AF_INET, &name.sin_addr, out_ip, max_len);
			close(sock);
			return 1;
		}
	} else if (af == AF_INET6) {
		struct sockaddr_in6 serv;
		memset(&serv, 0, sizeof(serv));
		serv.sin6_family = AF_INET6;
		if (inet_pton(AF_INET6, "2001:4860:4860::8888", &serv.sin6_addr) != 1) {
			close(sock);
			return 0;
		}
		serv.sin6_port = htons(53);

		if (connect(sock, (const struct sockaddr *)&serv, sizeof(serv)) < 0) {
			close(sock);
			return 0;
		}

		struct sockaddr_in6 name;
		socklen_t namelen = sizeof(name);
		if (getsockname(sock, (struct sockaddr *)&name, &namelen) == 0) {
			if (IN6_IS_ADDR_LINKLOCAL(&(name.sin6_addr))) {
				close(sock);
				return 0;
			}
			inet_ntop(AF_INET6, &name.sin6_addr, out_ip, max_len);
			close(sock);
			return 1;
		}
	}

	close(sock);
	return 0;
}

static bool
daemon_is_vpn_ifname(const char *name,
		     const struct vpnhide_iface_ioctl_data *prefixes)
{
	/* Use auto-generated static patterns */
	if (vpnhide_iface_is_vpn(name))
		return true;

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

static void update_spoof_ip(int fd, char *last_ipv4, char *last_ipv6)
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
		char ipv4[64];
		char ipv6[64];
		bool has_ipv4;
		bool has_ipv6;
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
					if (active_vpns.vpns[i].ifindex ==
					    vpn_idx) {
						dup = true;
						break;
					}
				}
				if (!dup &&
				    active_vpns.count < MAX_ACTIVE_VPNS) {
					active_vpns.vpns[active_vpns.count].ifindex = vpn_idx;
					strncpy(active_vpns.vpns[active_vpns.count].name, name, MAX_IFACE_LEN - 1);
					active_vpns.vpns[active_vpns.count].name[MAX_IFACE_LEN - 1] = '\0';
					active_vpns.count++;
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

			// Test IPv4 egress route
			char tmp_ipv4[64] = {0};
			if (test_interface_egress(name, AF_INET, tmp_ipv4, sizeof(tmp_ipv4))) {
				interfaces[idx].has_ipv4 = true;
				strcpy(interfaces[idx].ipv4, tmp_ipv4);
			}

			// Test IPv6 egress route
			char tmp_ipv6[64] = {0};
			if (test_interface_egress(name, AF_INET6, tmp_ipv6, sizeof(tmp_ipv6))) {
				interfaces[idx].has_ipv6 = true;
				strcpy(interfaces[idx].ipv6, tmp_ipv6);
			}
		}
	}

	freeifaddrs(ifaddr);

	/* Score all aggregated interfaces that have at least one active egress route */
	for (int i = 0; i < iface_count; i++) {
		struct iface_info *info = &interfaces[i];
		if (!info->has_ipv4 && !info->has_ipv6) {
			continue; // No internet access, ignore completely
		}

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

		/* Add priority for dual-stack or having active IPv4 / IPv6 */
		if (info->has_ipv4)
			info->score += 5000;
		if (info->has_ipv6)
			info->score += 5000;

		if (info->score > best_score) {
			best_score = info->score;
			strncpy(best_ifname, info->name, IFNAMSIZ - 1);
			best_ifname[IFNAMSIZ - 1] = '\0';
			if (info->has_ipv4) {
				strcpy(new_ipv4, info->ipv4);
			} else {
				strcpy(new_ipv4, "none");
			}
			if (info->has_ipv6) {
				strcpy(new_ipv6, info->ipv6);
			} else {
				strcpy(new_ipv6, "none");
			}
		}
	}

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
	update_spoof_ip(fd, last_ipv4, last_ipv6);

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
			update_spoof_ip(fd, last_ipv4, last_ipv6);

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
