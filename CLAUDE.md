# Skerry

Опенсорсный кроссплатформенный SSH-клиент — функциональный аналог Termius с единым ядром.
Этап: **каркас работает** — KMP-скаффолдинг собирается на всех таргетах, Compose-тема из
дизайн-токенов, SSH-коннект на desktop (sshj за `SshTransport`, интеграционные тесты на MINA SSHD).

## Зафиксированные решения (не пересматривать без запроса пользователя)

- **Стек**: Kotlin Multiplatform, UI — Compose Multiplatform (Android, Desktop JVM).
- **Платформы**: активны Desktop (Linux/Windows/macOS) + Android, паритет фич между ними.
  **iOS/iPadOS ОТЛОЖЕН** (исключён из проекта 2026-06-21, вернёмся позже — таргеты и `iosMain`
  удалены; крипта/стор остаются мультиплатформенно-готовыми, чтобы возврат был дешёвым).
- **MVP (Phase 1)**: SSH + SFTP + port forwarding, менеджер хостов/ключей, терминал, мастер-пароль + биометрия, локальное зашифрованное хранилище.
- **Phase 2**: self-hosted sync (модель Vaultwarden, E2E, zero-knowledge), сниппеты, AI-ассистент (BYOK/свой endpoint, политики per-host: Strict/Balanced/Permissive/Off).
- **Phase 3**: Mosh, Telnet, serial, autocomplete, локальная AI-модель на desktop.
- **Лицензии**: GPL-3.0 клиенты, AGPL-3.0 sync-сервер.
- **Дистрибуция desktop**: Linux — Flatpak/Flathub основной (манифест с `--socket=ssh-auth`) + .deb/.rpm в Releases; Windows — MSI + портативный ZIP; macOS — DMG без нотаризации на старте. Обновления через каналы; в аппе только проверка GitHub Releases API.

## Целевая структура кодовой базы

```
shared/        # ядро KMP: ssh/, sftp/, vault/, sync/, terminal/, ai/ (интерфейсы)
composeApp/    # весь UI один раз: commonMain + androidMain/desktopMain
server/        # self-hosted sync-сервер (Ktor, AGPL-3.0)
docs/          # прототипы и проектные документы (источник правды по UX и протоколу)
```

## Документы в docs/ (читать перед соответствующей работой)

- `coding-guidelines.md` — **ОБЯЗАТЕЛЬНО перед написанием любого кода**: правила, выведенные из
  массового рефакторинга 2026-07 (готовые абстракции, декомпозиция, корутины, безопасность,
  UI-токены, чек-лист самопроверки) — чтобы не понадобился следующий такой рефакторинг.
- `skerry-product-brief.md` — полный базис: решения, структура, фазы, принципы.
- `skerry-sync-design.md` — протокол sync: иерархия ключей (Argon2id → masterKey → authKey/dataKey, XChaCha20-Poly1305), модель VaultRecord, REST/WS API, LWW-конфликты, паринг, модель угроз.
- HTML-прототипы (открывать в браузере, навигация панелью «Prototype» внизу):
  `skerry-prototype.html` (desktop), `skerry-mobile-prototype.html` (телефон),
  `skerry-tablet-prototype.html` (iPad, split-view + двухпанельный SFTP),
  `skerry-sync-prototype.html` (админка sync-сервера).

## Дизайн-токены

Источник правды — `:root` в HTML-прототипах (палитра «night sea»). Ключевые: фон `#07141E`,
primary cyan `#2BBDEE` (active/focus/status), amber `#F2A65A` — ТОЛЬКО для AI/lighthouse-моментов,
success `#5DCE9E`, error `#E94B4B`, терминал `#050E16`, моноширинный — JetBrains Mono.
При скаффолдинге перенести в Compose-тему как единственный источник.

## Принципы продукта

Local-first (всё работает без сервера) · Zero-knowledge (мастер-пароль не покидает устройство) ·
AI under policy (вывод сервера = недоверенный источник, подтверждение перед выполнением) ·
Паритет платформ (фича не готова, пока не работает везде).

## Следующий шаг (с него начинать новую сессию)

**Детальная актуальная память — в `memory/MEMORY.md` (индекс) и файлах рядом; там источник
правды по «что сделано / что дальше», этот раздел — только крупные вехи.**

**Phase 1 (MVP) — ЗАКРЫТ.** SSH/SFTP/порт-форвардинг, менеджер хостов/ключей/групп,
терминал (grid-переписка, конформность VT, мышь), vault (крипто Argon2id→XChaCha20 +
высокоуровневый `Vault`/`VaultRecord` + UI мастер-пароля + биометрия + reset/recovery),
двухпанельный SFTP. Паритет desktop⇆Android закрыт (роадмап A–E), проверено на S24.

**Phase 2 — ЗАКРЫТ.** Self-hosted sync (Ktor+SRP-сервер, zero-knowledge E2E,
live-sync push-on-change, tombstone-propagation, персист курсора, селективный синк по типам,
admin-консоль); паринг устройств (QR, вариант B); сниппеты; **AI-ассистент (BYOK OpenAI +
per-host политики Strict/Balanced/Permissive/Off)** — код в `shared/ai/` и `ui/ai/`.
**Teams — СДЕЛАН (2026-07-05, по явному запросу).** Полный E2E zero-knowledge шеринг
хостов/сниппетов: X25519 sealed-envelope приглашения со сверкой фингерпринта, teamKey +
per-team vault поверх общего SyncEngine, роли owner/member, UI desktop+mobile, секции TEAMS
в списках хостов. Схема и модель угроз — `docs/skerry-sync-design.md` §6
(удаление участника = ACL-отзыв без ротации ключа, модель Bitwarden).

**Phase 3 core — ЗАКРЫТ.** Telnet (свой IAC-кодек), serial (desktop jSerialComm /
Android USB-OTG), autocomplete терминала с историей, desktop-хоткеи — реализовано и запушено.

**Полировка — ЗАВЕРШЕНА (2026-07).** LOW-хвосты предрелизного ревью и все отложенные
кандидаты рефакторинга закрыты (в т.ч. декомпозиция TerminalEmulator, `:sync-wire`,
типизированный SyncStatus.Failed, унификация mobile-форм — проверено на S24).

**Локальный AI — ЗАКРЫТ (2026-07).** Полная автономность: приложение само качает GGUF-модели
(докачка + sha256) и запускает их на устройстве (desktop + Android, Llamatik/llama.cpp за
`LocalLlmRuntime`); каталог Qwen3 1.7B/4B + Phi-4 Mini; Strict-политика работает через локальную
модель (`AiRouter`); UI выбора провайдера и менеджер моделей — desktop и mobile.

Дальше (следующая сессия): проверить локальный AI на S24, затем **подготовка к релизу** —
начать с Flatpak-манифеста (`--socket=ssh-auth`, Flathub — основной канал Linux), затем
.deb/.rpm, MSI + ZIP, DMG, README со скриншотами, проверка обновлений через GitHub Releases API.
Отложенные пункты (Mosh, планшетный режим) — не предлагать. Актуальный статус — в `memory/`.
