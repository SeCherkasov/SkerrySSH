---
name: skerry-terminal
description: "Change or diagnose the Skerry terminal emulator, byte stream, rendering, shell marks, input, clipboard or recording pipeline while preserving protocol and session invariants."
---

# Skerry: терминал и shell integration

## Карта цепочки

Transport/ShellChannel → TerminalScreenState / EmulatorOwnerLoop → shared TerminalEmulator → derived snapshots/rows → Compose terminal surface. Исходники: shared commonMain `terminal/`; UI/controller/rendering: composeApp commonMain `ui/terminal`; desktop input/clipboard/platform actual рядом в desktopMain. Начать с `rg --files ... | rg 'Terminal|EmulatorOwnerLoop|ShellMarks|Reflow'`.

Ключевые existing abstractions: SgrParser, CharMetrics, TerminalReflow, ShellMarks, TerminalStepMark/TerminalEchoFilter, SafeTerminalInput, SessionRecorder/Asciicast, CommandHistory/AutocompleteEngine. Layout/session ownership — SessionsController и PaneLayout, не emulator.

## Инварианты

- Byte stream lossless и FIFO. Производительность snapshot publication не даёт права выбрасывать bytes/escape state. Фрагментация escape sequence между reads нормальна; parser должен переживать chunk boundaries.
- Один owner мутирует emulator; UI читает согласованные derived snapshots. Не добавлять прямое чтение mutating rows из composable ради ускорения.
- Alternate screen, scrollback, soft-wrap/reflow, wide/combining glyphs, cursor и selection имеют связанные anchors. Resize/font/theme change должны обновить hit testing/layout cache без потери identity неизменных rows.
- Remote output untrusted: лимиты sequence/strings/allocations, bidi/control/concealment, OSC input не должен становиться произвольным локальным действием.
- Hidden tab можно реже публиковать full pixels/state для rendering, но prompt safety/cwd/command state продолжает получать нужные обновления. Split visible panes не считать hidden. Activation требует немедленной актуализации.
- Synchronized-output holds, cancellation и tail flush сохраняются при coalescing.

## Publication и performance

В исследованном коде есть coalesced публикация с обычной cadence около 16 ms и отдельный TerminalInputPublication для короткого feedback window после реально доставленного user input. При следующей задаче перечитать constants/callers: не менять глобальные интервалы по историческому числу.

Typing/paste/snippet/shared input/accepted autocomplete/sudo/raw user bytes должны различаться от программных protocol replies. Программный ответ не должен открывать окно «пользователь набирает». RowRenderCache/GlyphStyleCache/TerminalLinkScanCache уже решают части allocations/layout/scanning; сначала профилировать и искать existing cache.

## OSC 133/OSC 7 и runbook markers

ShellMarks разбирает prompt/command marks и cwd; anchors едут с scrollback/reflow. Не присваивать exit code чужой команде при пропущенном D/alternate screen. First-open cwd snapshot и позднее обновление OSC 7 — разные события: читать контракт и regression tests прежде чем объявлять их багом.

Runbook status проходит через TerminalStepMark и TerminalEchoFilter; runbook строит probes, terminal owns parser/echo semantics. Не парсить те же bytes вторым независимым механизмом в UI. Уточнять отношение marks к активной команде, session generation и reconnect.

## Input/clipboard/accessibility

- SafeTerminalInput и ProductionGuard участвуют в action path; не обходить через прямой write при новом shortcut/action.
- KeyboardClaim восстанавливает focus только владельцу клавиатуры; диалог или поле assistant не должны терять его автоматически.
- ImeFunnel преобразует input edit в bytes и reset buffer **внутри edit**, не через запоздалую recomposition. Typed password не должен оставаться EditableText semantics.
- Clipboard через SystemClipboard; на Wayland AWT может видеть другой buffer. Отказ read ≠ пустая строка, отказ write ≠ успешная запись в другой buffer.
- Selection/search/link hit-testing проверять при font/scale/reflow/scrollback, concealed text и soft wraps. Accessible terminal selection — отдельная доступная поверхность, не просто raw buffer dump.

## Тестовый набор под изменение

Parser: split input на каждой значимой границе, malformed/oversized sequences, unicode/surrogate/wide glyphs, alternate screen. Reflow: selection/cursor/shell marks при resize. State: concurrent input+output, hidden tabs, activation, cancel/EOF/reconnect, ordering. UI: реальное рисование/clipboard/keyboard/IME, не только snapshot equality.

Найти existing tests `ShellMarksTest`, terminal render/input/cache tests и `TerminalBackgroundSshLoadTest`; выбирать фактические имена через rg. Для performance — `skerry-performance`: пять SSH/PTYS, один visible/остальные hidden, output+typing/scrolling, measured p95 и resource conditions. Функциональная нагрузка не равна keyboard-to-pixel benchmark.

## Пользовательская проверка

Подключить dev SSH, открыть несколько вкладок/split; запустить непрерывный вывод, на активной печатать/scroll/search/copy; переключить hidden tab, изменить размер/font/theme, reconnect. Сверить prompt/cwd/exit indicators и отсутствие потери хвоста. Android IME/backspace/rotation и physical TalkBack проверять отдельно при relevant scope. Работать без production secrets и не сохранять чувствительные terminal transcripts в bundle/report.
