_2026-05-24_

## English

- WifiInfo hooks in system_server: restore IP/SSID/BSSID redacted by Android 12+ privacy controls (fixes MTS detection on Wi-Fi)
- Suppress VPN-specific network callbacks for target apps in system_server (fixes MTS detection on cellular networks)
- Add new diagnostic checks in the companion app to verify VPN callback suppression and WifiInfo unredaction

## Русский

- Хуки WifiInfo в system_server: восстановление IP/SSID/BSSID, которые Android 12+ скрывает для приложений без ACCESS_FINE_LOCATION (исправляет детект МТС на Wi-Fi)
- Блокировка специфичных для VPN сетевых коллбеков в system_server для целевых приложений (исправляет детект МТС на мобильных сетях)
- Добавлены новые диагностические проверки для верификации блокировки VPN-коллбеков и восстановления параметров WifiInfo
