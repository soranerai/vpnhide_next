_2026-06-28_

## English

kmod fib_nl_fill_rule + check_uid_route_rules_leak: fix detection of VPN rules with uid_range starting below 10000 — rules like [0..app_uid] where VPN routes from UID 0 up to the target app UID were not filtered (start=0 < 10000) and not detected; now both sides check (start >= 10000 || end >= 10000) && end != UINT_MAX, catching any rule that touches app UID space

## Русский

kmod fib_nl_fill_rule + check_uid_route_rules_leak: исправлен детект VPN-правил с uid_range начинающимся ниже 10000 — правила вида [0..app_uid], где VPN маршрутизирует трафик от UID 0 до целевого приложения, не фильтровались (start=0 < 10000) и не обнаруживались; теперь обе стороны проверяют (start >= 10000 || end >= 10000) && end != UINT_MAX, перехватывая любое правило затрагивающее пространство UID приложений
