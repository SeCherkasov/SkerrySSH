# Skerry

Опенсорсный кроссплатформенный SSH-клиент — функциональный аналог Termius с единым ядром.
Этап: **каркас работает** — KMP-скаффолдинг собирается на всех таргетах, Compose-тема из
дизайн-токенов, SSH-коннект на desktop (sshj за `SshTransport`, интеграционные тесты на MINA SSHD).

## Зафиксированные решения (не пересматривать без запроса пользователя)

- **Стек**: Kotlin Multiplatform, UI — Compose Multiplatform на всех платформах (Android, iOS, Desktop JVM).
- **Платформы**: все сразу, паритет фич с первого релиза (Linux/Windows/macOS/Android/iOS+iPadOS).
- **MVP (Phase 1)**: SSH + SFTP + port forwarding, менеджер хостов/ключей, терминал, мастер-пароль + биометрия, локальное зашифрованное хранилище.
- **Phase 2**: self-hosted sync (модель Vaultwarden, E2E, zero-knowledge), сниппеты, AI-ассистент (BYOK/свой endpoint, политики per-host: Strict/Balanced/Permissive/Off).
- **Phase 3**: Mosh, Telnet, serial, autocomplete, локальная AI-модель на desktop.
- **Лицензии**: GPL-3.0 клиенты, AGPL-3.0 sync-сервер.
- **Дистрибуция desktop**: Linux — Flatpak/Flathub основной (манифест с `--socket=ssh-auth`) + .deb/.rpm в Releases; Windows — MSI + портативный ZIP; macOS — DMG без нотаризации на старте. Обновления через каналы; в аппе только проверка GitHub Releases API.

## Целевая структура кодовой базы

```
shared/        # ядро KMP: ssh/, sftp/, vault/, sync/, terminal/, ai/ (интерфейсы)
composeApp/    # весь UI один раз: commonMain + androidMain/iosMain/desktopMain
server/        # self-hosted sync-сервер (Ktor, AGPL-3.0)
docs/          # прототипы и проектные документы (источник правды по UX и протоколу)
```

## Документы в docs/ (читать перед соответствующей работой)

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

Интерактивный терминал на desktop: shell-канал с PTY в `SshTransport`/`SshjTransport`
(сейчас есть только одноразовый exec), привязка к `TerminalSession`, минимальный
терминальный UI в composeApp (JetBrains Mono через compose-resources, фон `terminalBg`).
Следом — менеджер хостов с персистентным known-hosts (интерфейс `HostKeyVerifier` уже готов).
