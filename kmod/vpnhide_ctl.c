#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <linux/types.h>

// VpnHide IOCTL constants
#define VH_IOCTL_MAGIC 0x56

struct vpnhide_ioctl_data {
    int count;
    unsigned int uids[512];
};

#define VH_SET_TARGETS        _IOW(VH_IOCTL_MAGIC, 0x01, struct vpnhide_ioctl_data)
#define VH_SET_DIRECT_TARGETS _IOW(VH_IOCTL_MAGIC, 0x02, struct vpnhide_ioctl_data)
#define VH_SET_DEBUG          _IOW(VH_IOCTL_MAGIC, 0x03, int)
#define VH_SET_PHYS_IFINDEX   _IOW(VH_IOCTL_MAGIC, 0x04, int)
#define VH_SET_PORT_TARGETS   _IOW(VH_IOCTL_MAGIC, 0x05, struct vpnhide_ioctl_data)

void print_usage(const char *prog) {
    fprintf(stderr, "Usage: %s <targets|direct|port_targets|debug|phys> [args...]\n", prog);
}

int main(int argc, char **argv) {
    int fd, val;
    struct vpnhide_ioctl_data data;

    if (argc < 2) {
        print_usage(argv[0]);
        return 1;
    }

    // We use a dedicated misc device for control
    fd = open("/dev/vpnhide_ctrl", O_RDWR);
    if (fd < 0) {
        perror("open /dev/vpnhide_ctrl");
        fprintf(stderr, "Error: Is the kernel module loaded?\n");
        return 1;
    }

    if (strcmp(argv[1], "targets") == 0 || strcmp(argv[1], "direct") == 0 || strcmp(argv[1], "port_targets") == 0) {
        data.count = argc - 2;
        if (data.count > 512) data.count = 512;
        
        for (int i = 0; i < data.count; i++) {
            data.uids[i] = (unsigned int)atoi(argv[i + 2]);
        }
        
        unsigned long cmd;
        if (strcmp(argv[1], "targets") == 0) cmd = VH_SET_TARGETS;
        else if (strcmp(argv[1], "direct") == 0) cmd = VH_SET_DIRECT_TARGETS;
        else cmd = VH_SET_PORT_TARGETS;

        if (ioctl(fd, cmd, &data) < 0) {
            perror("ioctl");
            return 1;
        }
    } else if (strcmp(argv[1], "debug") == 0) {
        if (argc < 3) { print_usage(argv[0]); return 1; }
        val = atoi(argv[2]);
        if (ioctl(fd, VH_SET_DEBUG, &val) < 0) {
            perror("VH_SET_DEBUG");
            return 1;
        }
    } else if (strcmp(argv[1], "phys") == 0) {
        if (argc < 3) { print_usage(argv[0]); return 1; }
        val = atoi(argv[2]);
        if (ioctl(fd, VH_SET_PHYS_IFINDEX, &val) < 0) {
            perror("VH_SET_PHYS_IFINDEX");
            return 1;
        }
    } else {
        print_usage(argv[0]);
        return 1;
    }

    close(fd);
    return 0;
}
