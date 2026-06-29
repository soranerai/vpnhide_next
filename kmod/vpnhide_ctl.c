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
#include "parson.h"

void print_usage(const char *prog)
{
	fprintf(stderr,
		"Usage: %s <load|targets|port_targets|lsposed_targets|port_rules|iface_prefixes|set_spoof_ip|debug|active_hooks|java_hooks|stats> [args...]\n",
		prog);
	fprintf(stderr, "  load format: <json_path> [self_uid]\n");
	fprintf(stderr,
		"  port_rules format: <uid> <rule_count> <start> <end> <proto> ...\n");
	fprintf(stderr, "  proto: 0=TCP, 1=UDP, 2=BOTH\n");
	fprintf(stderr, "  set_spoof_ip format: <ipv4|none> <ipv6|none>\n");
}

static int add_uid_distinct(uid_t *arr, int *count, uid_t uid)
{
	if (uid == 0)
		return 0;
	for (int i = 0; i < *count; i++) {
		if (arr[i] == uid)
			return 0;
	}
	if (*count < MAX_TARGET_UIDS) {
		arr[(*count)++] = uid;
		return 1;
	}
	return 0;
}

static void sort_uids(uid_t *arr, int count)
{
	for (int i = 0; i < count - 1; i++) {
		for (int j = 0; j < count - i - 1; j++) {
			if (arr[j] > arr[j + 1]) {
				uid_t temp = arr[j];
				arr[j] = arr[j + 1];
				arr[j + 1] = temp;
			}
		}
	}
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

	if (strcmp(argv[1], "load") == 0) {
		if (argc < 3) {
			fprintf(stderr, "Error: load requires <json_path> [self_uid]\n");
			close(fd);
			return 1;
		}
		const char *json_path = argv[2];
		uid_t self_uid = 0;
		if (argc > 3) {
			self_uid = (uid_t)atoi(argv[3]);
		}

		JSON_Value *root_value = json_parse_file(json_path);
		if (!root_value || json_value_get_type(root_value) != JSONObject) {
			fprintf(stderr, "Failed to parse JSON file: %s\n", json_path);
			if (root_value)
				json_value_free(root_value);
			close(fd);
			return 1;
		}

		JSON_Object *root = json_value_get_object(root_value);

		// 1. Hook masks & debug level
		JSON_Object *global_config = json_object_get_object(root, "globalConfig");
		if (global_config) {
			unsigned int kernel_mask = (unsigned int)json_object_get_number(global_config, "kernelHookMask");
			unsigned int java_mask = (unsigned int)json_object_get_number(global_config, "javaHookMask");
			int debug_logging = (int)json_object_get_number(global_config, "debugLogging");

			if (ioctl(fd, VH_SET_ACTIVE_HOOKS, &kernel_mask) < 0) {
				perror("VH_SET_ACTIVE_HOOKS");
			}
			if (ioctl(fd, VH_SET_JAVA_HOOK_MASK, &java_mask) < 0) {
				perror("VH_SET_JAVA_HOOK_MASK");
			}
			if (ioctl(fd, VH_SET_DEBUG, &debug_logging) < 0) {
				perror("VH_SET_DEBUG");
			}
		}

		// 2. targets & lsposed_targets
		JSON_Array *apps = json_object_get_array(root, "apps");
		struct vpnhide_ioctl_data targets;
		struct vpnhide_ioctl_data lsposed;
		memset(&targets, 0, sizeof(targets));
		memset(&lsposed, 0, sizeof(lsposed));

		if (apps) {
			size_t apps_count = json_array_get_count(apps);
			for (size_t i = 0; i < apps_count; i++) {
				JSON_Object *app = json_array_get_object(apps, i);
				if (!app)
					continue;

				uid_t uid = (uid_t)json_object_get_number(app, "uid");
				int kmod = json_object_get_boolean(app, "kmod");
				int lsp = json_object_get_boolean(app, "lsposed");

				if (kmod && uid != 0) {
					add_uid_distinct(targets.uids, &targets.count, uid);
				}
				if (lsp && uid != 0) {
					add_uid_distinct(lsposed.uids, &lsposed.count, uid);
				}
			}
		}
		if (self_uid != 0) {
			add_uid_distinct(targets.uids, &targets.count, self_uid);
			add_uid_distinct(lsposed.uids, &lsposed.count, self_uid);
		}
		sort_uids(targets.uids, targets.count);
		sort_uids(lsposed.uids, lsposed.count);

		if (ioctl(fd, VH_SET_TARGETS, &targets) < 0) {
			perror("VH_SET_TARGETS");
		}
		if (ioctl(fd, VH_SET_LSPOSED_TARGETS, &lsposed) < 0) {
			perror("VH_SET_LSPOSED_TARGETS");
		}

		// 3. Interface prefixes
		JSON_Array *prefixes = json_object_get_array(root, "ifacePrefixes");
		struct vpnhide_iface_ioctl_data idata;
		memset(&idata, 0, sizeof(idata));
		if (prefixes) {
			size_t prefixes_count = json_array_get_count(prefixes);
			idata.count = prefixes_count < MAX_IFACE_PREFIXES ? prefixes_count : MAX_IFACE_PREFIXES;
			for (int i = 0; i < idata.count; i++) {
				const char *prefix_str = json_array_get_string(prefixes, i);
				if (prefix_str) {
					strncpy(idata.prefixes[i], prefix_str, MAX_IFACE_LEN - 1);
					idata.prefixes[i][MAX_IFACE_LEN - 1] = '\0';
				}
			}
		}
		if (ioctl(fd, VH_SET_IFACE_PREFIXES, &idata) < 0) {
			perror("VH_SET_IFACE_PREFIXES");
		}

		// 4. Port rules
		pdata.count = 0;
		JSON_Array *port_rules = json_object_get_array(root, "portRules");
		JSON_Array *mass_port_rules = json_object_get_array(root, "massPortRules");

		if (apps) {
			size_t apps_count = json_array_get_count(apps);
			for (size_t i = 0; i < apps_count; i++) {
				JSON_Object *app = json_array_get_object(apps, i);
				if (!app)
					continue;

				int port_hiding = json_object_get_boolean(app, "portHiding");
				uid_t uid = (uid_t)json_object_get_number(app, "uid");
				const char *package_name = json_object_get_string(app, "packageName");
				int user_id = (int)json_object_get_number(app, "userId");

				if (port_hiding && uid != 0 && package_name) {
					struct vpnhide_uid_port_rules *target = &pdata.targets[pdata.count];
					target->uid = uid;
					target->rule_count = 0;

					// Add app-specific enabled rules
					if (port_rules) {
						size_t rules_count = json_array_get_count(port_rules);
						for (size_t j = 0; j < rules_count; j++) {
							JSON_Object *rule = json_array_get_object(port_rules, j);
							if (!rule)
								continue;

							const char *rule_pkg = json_object_get_string(rule, "packageName");
							int rule_user = (int)json_object_get_number(rule, "userId");
							int enabled = json_object_get_boolean(rule, "enabled");

							if (rule_pkg && strcmp(rule_pkg, package_name) == 0 &&
							    rule_user == user_id && enabled) {
								if (target->rule_count < MAX_PORT_RULES_PER_UID) {
									target->rules[target->rule_count].start_port = (unsigned short)json_object_get_number(rule, "startPort");
									target->rules[target->rule_count].end_port = (unsigned short)json_object_get_number(rule, "endPort");
									const char *proto_str = json_object_get_string(rule, "protocol");
									unsigned char proto = VH_PROTO_BOTH;
									if (proto_str) {
										if (strcmp(proto_str, "TCP") == 0)
											proto = VH_PROTO_TCP;
										else if (strcmp(proto_str, "UDP") == 0)
											proto = VH_PROTO_UDP;
									}
									target->rules[target->rule_count].protocol = proto;
									target->rule_count++;
								}
							}
						}
					}

					// Add mass enabled rules
					if (mass_port_rules) {
						size_t mass_count = json_array_get_count(mass_port_rules);
						for (size_t j = 0; j < mass_count; j++) {
							JSON_Object *rule = json_array_get_object(mass_port_rules, j);
							if (!rule)
								continue;

							int enabled = json_object_get_boolean(rule, "enabled");
							if (enabled) {
								if (target->rule_count < MAX_PORT_RULES_PER_UID) {
									target->rules[target->rule_count].start_port = (unsigned short)json_object_get_number(rule, "startPort");
									target->rules[target->rule_count].end_port = (unsigned short)json_object_get_number(rule, "endPort");
									const char *proto_str = json_object_get_string(rule, "protocol");
									unsigned char proto = VH_PROTO_BOTH;
									if (proto_str) {
										if (strcmp(proto_str, "TCP") == 0)
											proto = VH_PROTO_TCP;
										else if (strcmp(proto_str, "UDP") == 0)
											proto = VH_PROTO_UDP;
									}
									target->rules[target->rule_count].protocol = proto;
									target->rule_count++;
								}
							}
						}
					}

					// Default block if no rules
					if (target->rule_count == 0) {
						target->rules[0].start_port = 0;
						target->rules[0].end_port = 65535;
						target->rules[0].protocol = VH_PROTO_BOTH;
						target->rule_count = 1;
					}
					pdata.count++;
				}
			}
		}

		if (ioctl(fd, VH_SET_PORT_RULES, &pdata) < 0) {
			perror("VH_SET_PORT_RULES");
		}

		json_value_free(root_value);
	} else if (strcmp(argv[1], "targets") == 0 ||
	    strcmp(argv[1], "port_targets") == 0 ||
	    strcmp(argv[1], "lsposed_targets") == 0) {
		data.count = argc - 2;
		if (data.count > MAX_TARGET_UIDS)
			data.count = MAX_TARGET_UIDS;

		for (int i = 0; i < data.count; i++) {
			data.uids[i] = (unsigned int)atoi(argv[i + 2]);
		}

		unsigned long cmd;
		if (strcmp(argv[1], "targets") == 0)
			cmd = VH_SET_TARGETS;
		else if (strcmp(argv[1], "port_targets") == 0)
			cmd = VH_SET_PORT_TARGETS;
		else
			cmd = VH_SET_LSPOSED_TARGETS;

		if (ioctl(fd, cmd, &data) < 0) {
			perror("ioctl");
			close(fd);
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
			close(fd);
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
			close(fd);
			return 1;
		}
	} else if (strcmp(argv[1], "set_spoof_ip") == 0) {
		struct vpnhide_spoof_ip spoof;
		memset(&spoof, 0, sizeof(spoof));

		if (argc < 4) {
			fprintf(stderr,
				"Error: set_spoof_ip requires <ipv4|none> <ipv6|none>\n");
			print_usage(argv[0]);
			close(fd);
			return 1;
		}

		if (strcmp(argv[2], "none") != 0) {
			if (inet_pton(AF_INET, argv[2], &spoof.ipv4_addr) !=
			    1) {
				fprintf(stderr, "Invalid IPv4 address: %s\n",
					argv[2]);
				close(fd);
				return 1;
			}
			spoof.has_ipv4 = 1;
		}

		if (strcmp(argv[3], "none") != 0) {
			if (inet_pton(AF_INET6, argv[3], spoof.ipv6_addr) !=
			    1) {
				fprintf(stderr, "Invalid IPv6 address: %s\n",
					argv[3]);
				close(fd);
				return 1;
			}
			spoof.has_ipv6 = 1;
		}

		if (ioctl(fd, VH_SET_SPOOF_IP, &spoof) < 0) {
			perror("VH_SET_SPOOF_IP");
			close(fd);
			return 1;
		}
	} else if (strcmp(argv[1], "debug") == 0) {
		if (argc < 3) {
			print_usage(argv[0]);
			close(fd);
			return 1;
		}
		val = atoi(argv[2]);
		if (ioctl(fd, VH_SET_DEBUG, &val) < 0) {
			perror("VH_SET_DEBUG");
			close(fd);
			return 1;
		}
	} else if (strcmp(argv[1], "active_hooks") == 0) {
		if (argc < 3) {
			unsigned int mask = 0;
			if (ioctl(fd, VH_GET_ACTIVE_HOOKS, &mask) < 0) {
				perror("VH_GET_ACTIVE_HOOKS");
				close(fd);
				return 1;
			}
			printf("%u\n", mask);
		} else {
			unsigned int mask =
				(unsigned int)strtoul(argv[2], NULL, 0);
			if (ioctl(fd, VH_SET_ACTIVE_HOOKS, &mask) < 0) {
				perror("VH_SET_ACTIVE_HOOKS");
				close(fd);
				return 1;
			}
		}
	} else if (strcmp(argv[1], "java_hooks") == 0) {
		if (argc < 3) {
			unsigned int mask = 0;
			if (ioctl(fd, VH_GET_JAVA_HOOK_MASK, &mask) < 0) {
				perror("VH_GET_JAVA_HOOK_MASK");
				close(fd);
				return 1;
			}
			printf("%u\n", mask);
		} else {
			unsigned int mask =
				(unsigned int)strtoul(argv[2], NULL, 0);
			if (ioctl(fd, VH_SET_JAVA_HOOK_MASK, &mask) < 0) {
				perror("VH_SET_JAVA_HOOK_MASK");
				close(fd);
				return 1;
			}
		}
	} else if (strcmp(argv[1], "hook_status") == 0) {
		char buf[256];
		memset(buf, 0, sizeof(buf));
		if (ioctl(fd, VH_GET_HOOK_STATUS, buf) < 0) {
			perror("VH_GET_HOOK_STATUS");
			close(fd);
			return 1;
		}
		for (int i = 0; buf[i] != '\0'; i++) {
			if (buf[i] == ';')
				putchar('\n');
			else
				putchar(buf[i]);
		}
		putchar('\n');
	} else if (strcmp(argv[1], "java_stats") == 0) {
		if (argc > 2 && strcmp(argv[2], "clear") == 0) {
			if (write(fd, "clear_stats", 11) < 0) {
				perror("write clear_stats");
				close(fd);
				return 1;
			}
		} else {
			char buf[4096];
			memset(buf, 0, sizeof(buf));
			if (ioctl(fd, VH_GET_JAVA_STATS, buf) < 0) {
				perror("VH_GET_JAVA_STATS");
				close(fd);
				return 1;
			}
			printf("%s", buf);
		}
	} else if (strcmp(argv[1], "stats") == 0) {
		if (argc > 2 && strcmp(argv[2], "clear") == 0) {
			if (ioctl(fd, VH_CLEAR_STATS) < 0) {
				perror("VH_CLEAR_STATS");
				close(fd);
				return 1;
			}
		} else {
			struct vpnhide_kmod_stats_data sdata;
			memset(&sdata, 0, sizeof(sdata));
			if (ioctl(fd, VH_GET_STATS, &sdata) < 0) {
				perror("VH_GET_STATS");
				close(fd);
				return 1;
			}
			for (int i = 0; i < sdata.count; i++) {
				printf("%u;%u;%u;%u;%u;%u;%u\n", sdata.stats[i].uid,
				       sdata.stats[i].ioctl_count,
				       sdata.stats[i].netlink_count,
				       sdata.stats[i].proc_count,
				       sdata.stats[i].sockopt_count,
				       sdata.stats[i].connect_count,
				       sdata.stats[i].getname_count);
			}
		}
	} else {
		print_usage(argv[0]);
		close(fd);
		return 1;
	}

	close(fd);
	return 0;
}
