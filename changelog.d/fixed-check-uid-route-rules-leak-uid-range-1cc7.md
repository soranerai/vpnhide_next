_2026-06-28_

## English

check_uid_route_rules_leak: replace naive uid>=10000 detection with span-based analysis (single-UID point rules such as OEM Doze/Work Profile/Clone App are now ignored; only carpet-bombing rules with span>1000 and catch-all markers with uid_end==99999/199999 are flagged as VPN), and fix detection of VPN rules whose uid_range starts below 10000 — rules like [0..app_uid], where the VPN routes from UID 0 up to the target app UID, were previously unfiltered; both sides of the range are now checked: (start >= 10000 || end >= 10000) && end != UINT_MAX

## Русский

check_uid_route_rules_leak: наивная проверка uid>=10000 заменена на анализ ширины диапазона (точечные системные правила вроде Doze/Рабочего профиля/Клонирования приложений теперь игнорируются; VPN определяется только по «ковровым» правилам с span>1000 и catch-all маркерам uid_end==99999/199999), а также исправлен детект VPN-правил с uid_range, начинающимся ниже 10000 — правила вида [0..app_uid], где VPN маршрутизирует трафик от UID 0 до целевого приложения, ранее не отфильтровывались; теперь проверяются обе границы диапазона: (start >= 10000 || end >= 10000) && end != UINT_MAX
