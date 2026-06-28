_2026-06-28_

## English

check_ipv6_link_local_bruteforce: fix NDP oracle false positive — sendto() synchronous failure (ENODEV for non-existent index) left MSG_ERRQUEUE empty for a non-async reason; now skip silently on send_ret < 0 before sleeping

## Русский

check_ipv6_link_local_bruteforce: исправлен ложный позитив NDP-оракула — синхронный сбой sendto() (ENODEV для несуществующего индекса) оставлял MSG_ERRQUEUE пустым по нe-асинхронной причине; теперь индекс молча пропускается при send_ret < 0 до sleep
