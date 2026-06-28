_2026-06-28_

## English

check_ipv6_link_local_bruteforce: add SIOCGIFNAME ioctl fallback (Step 1b) and SIOCGIFHWADDR hardware-type probe (Step 1c) to Pass 1 — SIOCGIFNAME goes through dev_ioctl() which kmod may not hook unlike /sys/class/net; SIOCGIFHWADDR ARPHRD_NONE (65534) / ARPHRD_PPP (512) proves conclusively no L2 hardware regardless of interface name obfuscation

## Русский

check_ipv6_link_local_bruteforce: добавлен fallback через SIOCGIFNAME ioctl (Step 1b) и проверка аппаратного типа SIOCGIFHWADDR (Step 1c) в Pass 1 — SIOCGIFNAME использует dev_ioctl(), который kmod может не перехватывать в отличие от /sys/class/net; ARPHRD_NONE (65534) / ARPHRD_PPP (512) доказывают отсутствие L2-железа независимо от обфускации имени интерфейса
