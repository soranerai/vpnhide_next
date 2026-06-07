_2026-06-07_

## English

TrafficStats volume anomaly check now uses /proc/net/dev as ground truth to detect partial BPF-laundering failures that previously produced false-green results; iface_stats laundering implemented via two-pass BPF_MAP_LOOKUP_BATCH post-processing (collect VPN bytes, add to cover interface)

## Русский

Проверка аномалий TrafficStats теперь использует /proc/net/dev как эталон для обнаружения частичных сбоев BPF-лаундеринга, которые ранее давали ложно-зелёный результат; лаундеринг iface_stats реализован через двухпроходную постобработку BPF_MAP_LOOKUP_BATCH (сбор VPN-байт и добавление к cover-интерфейсу)
