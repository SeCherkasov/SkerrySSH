---
name: skerry-performance
description: "Measure and improve Skerry CPU, memory, UI/terminal latency, startup, transfer or vault performance using representative workloads and bounded resources. Use for an explicit performance objective."
---

# Skerry: производительность и нагрузочные проверки

## Начать с полезного результата

Сформулировать пользовательский сценарий и метрику до реализации: key-to-observed-state latency, frame time, startup-to-window/ready, transfer throughput/cancel latency, encrypted import/unlock/merge time, CPU/RSS. Зафиксировать acceptable regression и критерий keep/revert. Небольшой synthetic gain с усложнением runtime/ростом памяти не считать автоматически полезной оптимизацией.

Сначала проверить existing mechanisms: TerminalScreenState/EmulatorOwnerLoop coalescing/hidden publication, GlyphStyleCache/RowRenderCache/TerminalLinkScanCache, catalog virtualization, VaultRecordCodec/IndexedVaultRecords, atomic bulk import, changedTypes sync reload, SFTP request/transfer windows. Не переписывать stack или вводить кеш до нахождения hot path.

## Resource contract

Каждый compiler/JVM test/benchmark/stress process tree под user cgroup 3 GiB RAM, MemorySwapMax=0, CPUQuota=200%; минимум 6 GiB MemAvailable и swap не full. Через `skerry-new-machine/scripts/bounded_run.py`, последовательные runs, никакого reviewer рядом с Gradle. Bench child processes тоже в cgroup; внешний Docker daemon/remote host требует отдельных проверенных limits.

При cap hit делить workload/запуски/свежие JVM, не повышать лимит. Для timing pinning выбрать **два разных физических ядра** по актуальному `lscpu -e=CPU,CORE,SOCKET`, не два SMT siblings. CPUQuota ограничивает время, не гарантирует affinity. Не менять глобальный governor/swap/system limits без прямой задачи.

## Метод измерения

1. Зафиксировать commit/diff, OS/CPU/RAM, JDK/native dependency, renderer/display, screen/font/rows, network latency/data rate, dataset и cache state.
2. Подготовить deterministic dev fixture без production secrets. Считать размер/количество, не только название «большой vault».
3. Baseline и candidate в одинаковых условиях: warmup отдельно, несколько samples, median/p95/dispersion по смыслу метрики. Не сравнивать cold cache с warm cache и разные renderers.
4. Root build использует Kover instrumentation. Для timings поддержан `-Pskerry.bench`, отключающий его; одинаково на обеих сторонах. Не сравнивать instrumented baseline с uninstrumented candidate.
5. Сначала profile (JFR/allocations/CPU samples или подходящий инструмент), затем узкая правка largest relevant cost, повторный measurement и correctness tests.
6. Отчёт вне checkout: workload/commands/raw aggregate/conditions/baseline/candidate/tradeoff/live gaps. Не обещать blanket процент всему приложению.

## Представительные workloads

**Терминал:** пять SSH/PTYS, один visible + четыре hidden, continuous logs, typing/paste/scroll/search и activation; отдельно split-visible. Измерить input publication/observed state и CPU/allocations. Include remote-response latency case: при медленном сервере локальная cadence может не дать user gain. Функциональная нагрузка «не упало и bytes на месте» не измеряет FPS или physical key-to-pixel.

**Vault/catalog:** 1k/10k encrypted records, real crypto vs fake crypto явно разделены; import batch, load, unlock+catalog, merge. In-memory FS time — processing benchmark, не real disk latency. Проверить authenticated ids/atomic replacement/LWW после оптимизации; UI virtualization дополнить реалистичной navigability.

**SFTP:** file sizes/count, local/remote FS, network RTT/bandwidth, request window, очередь cancel/final states. Измерить tail latency и bytes correctness, не только best throughput.

**Startup:** launch-to-window и launch-to-ready отдельно; dependency init и first-use cost. Deferring expensive object может ухудшить первое подключение/unlock; измерить его. Packaged app и Gradle run — разные пути.

**RDP/VNC:** negotiated codecs/depth/resolution, update rate, viewport/renderer, input/audio, CPU/RSS, end-to-end frame/input latency при возможности; synthetic decoder speed не равна remote desktop responsiveness.

## Как не закрепить случайный выигрыш

Новый runtime flag/CDS/cache/binary rewrite добавляет complexity и platform surface. Проверить реальный эффект и цену RSS/first-use/compatibility. Не отключать security validation/crypto/authenticated metadata ради числа. Не сохранять оптимизацию, которая проиграла заранее согласованным критериям, только потому что на неё потрачено время.

Final gate с обычной корректной конфигурацией, required reviews и live relevant scenario. В PR — Measured improvement с одинаковыми единицами/условиями и конкретным Not verified live. Старые исторические числа не переносить как текущий baseline или обещанный эффект.
