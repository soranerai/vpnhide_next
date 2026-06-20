_2026-06-19_

## English

Optimize all kretprobe hooks to return 1 early from entry handlers for non-target UIDs and non-matching requests, skipping return handler execution and releasing kretprobe resources instantly

## Русский

Оптимизация всех хуков kretprobe для быстрого возврата 1 из входных обработчиков для нецелевых процессов и несовпадающих запросов, предотвращая вызов обработчиков возврата и мгновенно освобождая ресурсы kretprobe
