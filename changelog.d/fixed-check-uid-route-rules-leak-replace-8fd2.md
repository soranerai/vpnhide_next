_2026-06-27_

## English

check_uid_route_rules_leak: replace naive uid>=10000 detection with span-based analysis — single-UID point rules (OEM Doze/Work Profile/Clone App) are now ignored; only carpet-bombing rules (span>1000) and catch-all markers (uid_end==99999/199999) are flagged as VPN

## Русский

check_uid_route_rules_leak: заменена наивная проверка uid>=10000 на анализ ширины диапазона — точечные правила системы (Doze/Рабочий профиль/Клонирование) игнорируются; детектируются только «ковровые» правила (span>1000) и catch-all маркеры (uid_end==99999/199999)
