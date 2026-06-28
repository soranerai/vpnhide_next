_2026-06-27_

## English

Fix path existence oracle leaks for VPN interface subdirectories under sysctl/sysfs (/proc/sys/net, /sys/class/net) by hooking path-based syscalls (faccessat, newfstatat, openat, readlinkat)

## Русский

Исправлена утечка путей VPN-интерфейсов через оракулы существования директорий в sysctl/sysfs (/proc/sys/net, /sys/class/net) с помощью перехвата системных вызовов работы с путями (faccessat, newfstatat, openat, readlinkat)
