_2026-06-28_

## English

remove check_arp_timeout_illusion and check_broadcast_blackhole — ARP timeout oracle is conceptually identical to the existing NDP Timeout Oracle in check_ipv6_link_local_bruteforce (both test IFF_NOARP via L2 neighbor resolution absence); broadcast blackhole test (SO_BROADCAST to 255.255.255.255) is covered by the same ARPHRD-based L2 detection

## Русский

удалены check_arp_timeout_illusion и check_broadcast_blackhole — ARP timeout oracle концептуально идентичен NDP Timeout Oracle в check_ipv6_link_local_bruteforce (оба проверяют IFF_NOARP через отсутствие L2 neighbour resolution); проверка broadcast blackhole (SO_BROADCAST на 255.255.255.255) перекрывается ARPHRD-детекцией L2-отсутствия
