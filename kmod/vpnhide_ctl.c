#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <linux/types.h>

#include "include/vpnhide.h"

void print_usage(const char *prog)
{
	fprintf(stderr,
		"Usage: %s <targets|port_targets|port_rules|iface_prefixes|set_spoof_ip|debug|active_hooks|stats> [args...]\n",
		prog);
	fprintf(stderr,
		"  port_rules format: <uid> <rule_count> <start> <end> <proto> ...\n");
	fprintf(stderr, "  proto: 0=TCP, 1=UDP, 2=BOTH\n");
	fprintf(stderr, "  set_spoof_ip format: <ipv4|none> <ipv6|none>\n");
}

int main(int argc, char **argv)
{
	int fd, val;
	struct vpnhide_ioctl_data data;
	struct vpnhide_port_ioctl_data pdata;

	memset(&data, 0, sizeof(data));
	memset(&pdata, 0, sizeof(pdata));

	if (argc < 2) {
		print_usage(argv[0]);
		return 1;
	}

	fd = open("/dev/vpnhide_ctrl", O_RDWR);
	if (fd < 0) {
		perror("open /dev/vpnhide_ctrl");
		return 1;
	}

	if (strcmp(argv[1], "targets") == 0 ||
	    strcmp(argv[1], "port_targets") == 0) {
		data.count = argc - 2;
		if (data.count > MAX_TARGET_UIDS)
			data.count = MAX_TARGET_UIDS;

		for (int i = 0; i < data.count; i++) {
			data.uids[i] = (unsigned int)atoi(argv[i + 2]);
		}

		unsigned long cmd;
		if (strcmp(argv[1], "targets") == 0)
			cmd = VH_SET_TARGETS;
		else
			cmd = VH_SET_PORT_TARGETS;

		if (ioctl(fd, cmd, &data) < 0) {
			perror("ioctl");
			return 1;
		}
	} else if (strcmp(argv[1], "port_rules") == 0) {
		int arg_idx = 2;
		pdata.count = 0;

		while (arg_idx < argc && pdata.count < MAX_TARGET_UIDS) {
			struct vpnhide_uid_port_rules *target =
				&pdata.targets[pdata.count];
			if (arg_idx >= argc)
				break;
			target->uid = (uid_t)atoi(argv[arg_idx++]);
			if (arg_idx >= argc)
				break;
			int rules_to_parse = atoi(argv[arg_idx++]);

			if (rules_to_parse > MAX_PORT_RULES_PER_UID)
				rules_to_parse = MAX_PORT_RULES_PER_UID;

			target->rule_count = 0;
			for (int i = 0; i < rules_to_parse; i++) {
				if (arg_idx + 2 >= argc)
					break;
				target->rules[i].start_port =
					(unsigned short)atoi(argv[arg_idx++]);
				target->rules[i].end_port =
					(unsigned short)atoi(argv[arg_idx++]);
				target->rules[i].protocol =
					(unsigned char)atoi(argv[arg_idx++]);
				target->rule_count++;
			}
			pdata.count++;
		}

		if (ioctl(fd, VH_SET_PORT_RULES, &pdata) < 0) {
			perror("VH_SET_PORT_RULES");
			return 1;
		}
	} else if (strcmp(argv[1], "iface_prefixes") == 0) {
		struct vpnhide_iface_ioctl_data idata;
		memset(&idata, 0, sizeof(idata));
		idata.count = argc - 2;
		if (idata.count > MAX_IFACE_PREFIXES)
			idata.count = MAX_IFACE_PREFIXES;

		for (int i = 0; i < idata.count; i++) {
			strncpy(idata.prefixes[i], argv[i + 2],
				MAX_IFACE_LEN - 1);
			idata.prefixes[i][MAX_IFACE_LEN - 1] = '\0';
		}

		if (ioctl(fd, VH_SET_IFACE_PREFIXES, &idata) < 0) {
			perror("VH_SET_IFACE_PREFIXES");
			return 1;
		}
	} else if (strcmp(argv[1], "set_spoof_ip") == 0) {
		struct vpnhide_spoof_ip spoof;
		memset(&spoof, 0, sizeof(spoof));

		if (argc < 4) {
			fprintf(stderr,
				"Error: set_spoof_ip requires <ipv4|none> <ipv6|none>\n");
			print_usage(argv[0]);
			return 1;
		}

		if (strcmp(argv[2], "none") != 0) {
			if (inet_pton(AF_INET, argv[2], &spoof.ipv4_addr) !=
			    1) {
				fprintf(stderr, "Invalid IPv4 address: %s\n",
					argv[2]);
				return 1;
			}
			spoof.has_ipv4 = 1;
		}

		if (strcmp(argv[3], "none") != 0) {
			if (inet_pton(AF_INET6, argv[3], spoof.ipv6_addr) !=
			    1) {
				fprintf(stderr, "Invalid IPv6 address: %s\n",
					argv[3]);
				return 1;
			}
			spoof.has_ipv6 = 1;
		}

		if (ioctl(fd, VH_SET_SPOOF_IP, &spoof) < 0) {
			perror("VH_SET_SPOOF_IP");
			return 1;
		}
	} else if (strcmp(argv[1], "debug") == 0) {
		if (argc < 3) {
			print_usage(argv[0]);
			return 1;
		}
		val = atoi(argv[2]);
		if (ioctl(fd, VH_SET_DEBUG, &val) < 0) {
			perror("VH_SET_DEBUG");
			return 1;
		}
	} else if (strcmp(argv[1], "active_hooks") == 0) {
		if (argc < 3) {
			unsigned int mask = 0;
			if (ioctl(fd, VH_GET_ACTIVE_HOOKS, &mask) < 0) {
				perror("VH_GET_ACTIVE_HOOKS");
				return 1;
			}
			printf("%u\n", mask);
		} else {
			unsigned int mask =
				(unsigned int)strtoul(argv[2], NULL, 0);
			if (ioctl(fd, VH_SET_ACTIVE_HOOKS, &mask) < 0) {
				perror("VH_SET_ACTIVE_HOOKS");
				return 1;
			}
		}
	} else if (strcmp(argv[1], "stats") == 0) {
		if (argc > 2 && strcmp(argv[2], "clear") == 0) {
			if (ioctl(fd, VH_CLEAR_STATS) < 0) {
				perror("VH_CLEAR_STATS");
				return 1;
			}
		} else {
			struct vpnhide_kmod_stats_data sdata;
			memset(&sdata, 0, sizeof(sdata));
			if (ioctl(fd, VH_GET_STATS, &sdata) < 0) {
				perror("VH_GET_STATS");
				return 1;
			}
			for (int i = 0; i < sdata.count; i++) {
				printf("%u;%u;%u;%u;%u\n", sdata.stats[i].uid,
				       sdata.stats[i].ioctl_count,
				       sdata.stats[i].netlink_count,
				       sdata.stats[i].connect_count,
				       sdata.stats[i].getname_count);
			}
		}
	} else {
		print_usage(argv[0]);
		return 1;
	}

	close(fd);
	return 0;
}
