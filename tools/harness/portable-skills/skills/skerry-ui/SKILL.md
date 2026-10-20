---
name: skerry-ui
description: "Build or audit Skerry Compose desktop and mobile UI using project primitives, shared state, design prototypes, five locales and accessibility contracts."
---

# Skerry: UI, локализация и доступность

## Перед изменением

Прочитать `docs/coding-guidelines.md` UI catalogue, `docs/design/Skerry Tablet.html`, при brand работе — `Skerry Logo.html`. Посмотреть существующий экран и `docs/screenshots`. Прототип задаёт chrome; новую toolbar/menu структуру не придумывать без пользовательской задачи.

Общий UI: `composeApp/src/commonMain/kotlin/app/skerry/ui`. Desktop actual: `src/desktopMain`, Android actual: `src/androidMain`. Common form state → два тонких layout, не две реализации business logic.

## Готовые primitives

| Сценарий | Использовать |
|---|---|
| Текст/иконка/цвет | Txt / Sym / D.* tokens |
| Field caption + input | FormField + Modifier.fieldName(), desktop ModalTextField / mobile MobileFormInput |
| Buttons/toggles/chips/rows | существующие primitives из ui/design, не raw Material при наличии аналога |
| Popup action menu | MenuPanel + MenuItem/MenuActionRow; scrolling внутри panel |
| Confirmation/info | ConfirmActionDialog / NoticeDialog |
| Quoted executable command | CommandQuote + ClippedNotice; list version CommandLine |
| External label/host/note | untrustedLabel / sanitizeServerHost / sanitizeServerText |
| Server/team tag | tagChipLabel, normalized Tags |
| Folders/group selection | Folders/FolderSectionHeader/FolderCollapse; GroupSelectField и mobile counterpart |
| Focus после modal/blur | KeyboardClaim, handsKeyboardBack |
| IME в session | ImeFunnel, буфер без typed secrets в semantics |
| Native clipboard | rememberSystemClipboard; secret clipboard имеет отдельный sensitivity/clear contract |
| Status announcements | StatusAnnouncer, стабильная node над conditional branch |
| Stable test navigation | UiTags и ShellHarness |

Точная сигнатура/все варианты — в исходниках и каталоге; не копировать helper из этого списка вместо вызова existing primitive. remember должен иметь ключи, зависящие от settings: font metrics/hit testing меняются вместе с шрифтом/scale.

## Локализация

`composeApp/src/commonMain/composeResources/values` = English; рядом values-ru, values-zh, values-tr, values-de. Все новые key и placeholders одновременно во всех пяти. Разделять strings по текущим файлам (например strings_conn.xml), не собирать новый монолит.

- UI literals не хардкодить. User-facing текст короткий, технический; ошибки domain представлять типизированной причиной, локализовать в UI (пример SyncFailureReason + syncFailureText).
- Caps через labelUppercase, не String.uppercase: Turkish casing иной. Caption хранится в sentence case, primitive меняет только рисование.
- Compose Resources не AAPT: `\n`, `\t`, `\uXXXX`, `\\` декодируются; `\"`/`\'` отображаются с обратной чертой. Для кавычек использовать типографские marks: en/zh/tr “ ”, de „ “, ru « ».
- Substitution `%N$s`/`%N$d` по regex, не String.format. `%s` не подставится, `%%` не превратится автоматически в один процент.
- Проверка StringEscapeTest есть в composeApp desktopTest/i18n. Translation completeness и placeholder matching проверяются harness; это не проверка смысла перевода или реальной длины на экране.

## Accessibility и input

Каждое поле имеет accessible name, не просто соседний видимый Txt. StatusAnnouncer остаётся composed до смены текста; live region, вставленный вместе с сообщением, может ничего не объявить. Длинная команда wrap+bounded scroll, controls/bidi показаны безопасно; визуально обрезанную команду нельзя выдавать за полностью представленную.

Keyboard shortcut должен получить строку Settings → Keyboard в той же правке. Проверить Enter/Escape/tab traversal, keyboard ownership после dialog/window blur; не отбирать фокус у рядом открытого input. Mobile IME проверить цифры, backspace, paste, composition и rotation. Общий JVM тест не заменяет реальный Android keyboard.

## Проверка UI

1. State/form tests на shared logic и сохранение/reopen.
2. Desktop Compose `runDesktopShell` / `runMobileShell` / `runForm` по ShellHarness. Проверять результат действия, focus/semantics/error states, стабильные tags.
3. Рендер desktop и общий mobile layout: узкое/широкое окно, светлая/тёмная тема и затронутые theme colors, длинные ru/de и Turkish caps, scale/font change.
4. Реальный desktop application path; Android device/emulator при изменении platform path. Для TalkBack/physical keyboard/IME отметить конкретно что проверено и что нет.
5. Указать пользователю точный путь к экрану и воспроизводимые шаги. Скриншот полезен для layout, но один кадр не доказывает animation/focus/lifecycle.

Не выводить чувствительные значения на screenshot. Использовать demo/dev vault и тестовые hosts. Изменения ресурсов, assets/native libs входят в product inputs harness, это не docs-only change.
