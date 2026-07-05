_2026-06-28_

## English

check_ipv6_link_local_bruteforce: add if_indextoname resolution and an IPv6 PMTU oracle (EMSGSIZE on a 1450-byte sendto confirms a hidden tunnel even when netlink listing is filtered); add a fallback probe path for when anonymous_indices is empty (kernel intercepting if_indextoname) that automatically probes the 10 indices beyond the highest active one, with Passes 2-4 using an EINVAL-only multicast criterion on the fallback pool (results annotated [fallback]); add a SIOCGIFNAME ioctl fallback (Step 1b, via dev_ioctl() which kmod may not hook unlike /sys/class/net) and a SIOCGIFHWADDR hardware-type probe (Step 1c) to Pass 1, proving the absence of L2 hardware via ARPHRD_NONE/ARPHRD_PPP regardless of interface name obfuscation

## Русский

check_ipv6_link_local_bruteforce: добавлено определение имени через if_indextoname и IPv6 PMTU-оракул (EMSGSIZE при отправке 1450 байт подтверждает скрытый туннель даже при фильтрации netlink); добавлен fallback-путь зондирования на случай, если anonymous_indices пуст (ядро перехватывает if_indextoname) — автоматически проверяются 10 индексов за последним активным, Passes 2-4 используют на fallback-пуле критерий только EINVAL для мультикаста (результаты помечаются [fallback]); в Pass 1 добавлены fallback через SIOCGIFNAME ioctl (Step 1b, через dev_ioctl(), который kmod может не перехватывать в отличие от /sys/class/net) и проверка аппаратного типа SIOCGIFHWADDR (Step 1c) — ARPHRD_NONE/ARPHRD_PPP доказывают отсутствие L2-железа независимо от обфускации имени интерфейса
