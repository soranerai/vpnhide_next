_2026-06-28_

## English

check_ipv6_link_local_bruteforce: add fallback probe path — when anonymous_indices is empty (kernel intercepting if_indextoname), automatically probe the 10 indices beyond the highest active one; Passes 2-4 run on fallback pool with EINVAL-only multicast criterion (ENODEV excluded to avoid false positives on non-existent indices); results annotated with [fallback]

## Русский

check_ipv6_link_local_bruteforce: добавлен fallback-путь зондирования — если anonymous_indices пуст (ядро перехватывает if_indextoname), автоматически проверяются 10 индексов за последним активным; Passes 2–4 работают на fallback-пуле с критерием только EINVAL для мультикаста (ENODEV исключён во избежание ложных срабатываний на несуществующих индексах); результаты помечаются [fallback]
