_2026-05-31_

## English

Added security_socket_bind kernel hook to silently redirect blocked loopback port binds to port 0, making bind conflict scanning succeed transparently.

## Русский

Добавлен хук ядра security_socket_bind для незаметного перенаправления заблокированных привязок портов на loopback на порт 0, благодаря чему сканирование портов через bind() успешно завершается.
