<div align="center">

<img src="docs/img/readme-cover.png" alt="Skerry — SSH для десктопа и Android, шифрованное хранилище и опциональная синхронизация через собственный сервер." width="1200">

[English](README.md) · **Русский** · [简体中文](README.zh.md)

Skerry — SSH-клиент с открытым исходным кодом для Linux, Windows, macOS и Android.
Поддерживает SFTP, проброс портов, VNC/RDP и шифрованное хранилище учётных данных.

Для локальной работы аккаунт не нужен. Опциональная синхронизация устройств использует собственный сервер.

**[Скачать Skerry](https://github.com/SeCherkasov/SkerrySSH/releases/latest)** · [Руководство](https://github.com/SeCherkasov/SkerrySSH/wiki/Home-ru) · [Сообщить об ошибке](https://github.com/SeCherkasov/SkerrySSH/issues/new/choose)

[![Release](https://img.shields.io/github/v/release/SeCherkasov/SkerrySSH?color=2fc8b4)](https://github.com/SeCherkasov/SkerrySSH/releases/latest)
[![CI](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml/badge.svg)](https://github.com/SeCherkasov/SkerrySSH/actions/workflows/ci.yml)
[![Clients: GPL-3.0](https://img.shields.io/badge/clients-GPL--3.0-4b6475)](LICENSE)
[![Server: AGPL-3.0](https://img.shields.io/badge/server-AGPL--3.0-4b6475)](server/LICENSE)

[Возможности](#features) · [Скриншоты](#screenshots) · [Установка](#install) · [Приватность](#privacy) · [Разработка](#build)

</div>

<a id="features"></a>

## Возможности

| Компонент | Возможности |
| :--- | :--- |
| **Подключения** | SSH, Mosh, jump-хосты и импорт SSH-конфигурации. |
| **Терминал** | До четырёх панелей на вкладку, синхронный ввод, поиск по выводу и запись сессий. |
| **SFTP** | Двухпанельный файловый менеджер, редактор файлов и очередь передач. |
| **Туннели** | Local, remote и SOCKS forwarding с сохранением настроек. |
| **Рабочие столы** | VNC и RDP с обменом буфером. |
| **Управление** | Метрики, оповещения, оболочки Docker/Kubernetes, сниппеты и ранбуки. |
| **Production guard** | Подтверждение рискованных команд на хостах с тегом `prod`. |
| **Совместная работа** | Шифрованный обмен хостами, сниппетами и ранбуками; совместные терминальные сессии. |

<details>
<summary>Дополнительные возможности</summary>

- **Подключения:** SSH-сертификаты, keyboard-interactive аутентификация, Telnet и serial; импорт из `~/.ssh/config`.
- **Терминал:** история команд, трансляция ввода и запись asciinema v2 с воспроизведением.
- **Файлы и туннели:** просмотр файлов, фильтрация по имени и проброс обнаруженных портов в один клик.
- **Сессии:** настройки VNC/RDP меняются во время подключения; в совместном терминале доступны просмотр и передача управления.
- **Ранбуки:** последовательное выполнение команд и шаги передачи файлов.

</details>

<a id="screenshots"></a>

## Скриншоты

<table>
<tr>
<td width="50%" align="center"><strong>Разделённый терминал</strong><br><br><a href="docs/screenshots/panes.webp"><img src="docs/screenshots/panes.webp" alt="Четыре панели терминала с синхронным вводом" width="460"></a></td>
<td width="50%" align="center"><strong>SFTP-менеджер</strong><br><br><a href="docs/screenshots/sftp.webp"><img src="docs/screenshots/sftp.webp" alt="Локальные и удалённые файлы в SFTP-менеджере" width="460"></a></td>
</tr>
<tr>
<td width="50%" align="center"><strong>Шифрованное хранилище</strong><br><br><a href="docs/screenshots/vault.webp"><img src="docs/screenshots/vault.webp" alt="Хранилище ключей, паролей и сертификатов" width="460"></a></td>
<td width="50%" align="center"><strong>Ранбуки</strong><br><br><a href="docs/screenshots/runbooks.webp"><img src="docs/screenshots/runbooks.webp" alt="Ранбук с командами и передачей файлов по SFTP" width="460"></a></td>
</tr>
</table>

*Нажмите на изображение, чтобы открыть его в полном размере.*

### Android

Интерфейс Android включает дополнительный ряд клавиш терминала и биометрическую разблокировку хранилища.

<p align="center">
  <img src="docs/screenshots/mobile-hosts.webp" alt="Группы и теги хостов на Android" width="220">
  &nbsp;&nbsp;
  <img src="docs/screenshots/mobile-terminal.webp" alt="Терминал Android с дополнительным рядом клавиш" width="220">
</p>

Тёмная, светлая и системная темы. Интерфейс на **английском, русском, упрощённом китайском, турецком и немецком**.

<details>
<summary>Ещё экраны — терминал, туннели, сниппеты, AI и команды</summary>

#### Терминал

![Терминал](docs/screenshots/terminal.webp)

#### Проброс портов

![Проброс портов](docs/screenshots/tunnels.webp)

#### Сниппеты

![Сниппеты](docs/screenshots/snippets.webp)

#### Настройки AI

![Настройки AI](docs/screenshots/ai.webp)

#### Команды

![Команды](docs/screenshots/teams.webp)

</details>

*Обложка — презентационная иллюстрация на основе интерфейса приложения. На скриншотах используются демонстрационные хосты и данные.*

<a id="install"></a>

## Установка Skerry

Скачайте пакет для своей платформы из **[последнего релиза](https://github.com/SeCherkasov/SkerrySSH/releases/latest)**.
Skerry активно развивается и пока находится на стадии **pre-1.0**.

| Платформа | Архитектура | Пакеты |
| :--- | :--- | :--- |
| Linux | x86_64 · arm64 | `.deb` · `.rpm` · `.AppImage` |
| Windows | x64 | `.msi` · `.zip` |
| macOS | Apple Silicon · Intel | `.dmg` |
| Android 8.0+ | arm64-v8a | `.apk` |

ZIP-архив Windows — портативная версия.

1. Установите Skerry и создайте локальное хранилище с мастер-паролем.
2. Добавьте хост и учётные данные или импортируйте SSH-конфигурацию на десктопе.
3. Откройте сессию. Файлы, мониторинг и проброс портов доступны рядом с терминалом.

Берегите мастер-пароль: **восстановить его невозможно**.

<details>
<summary>Примечания к установке — подписи, контрольные суммы и версия macOS</summary>

Десктопные установщики пока не подписаны; сборки macOS не нотарифицированы.
Windows SmartScreen или macOS Gatekeeper могут запросить разрешение при первом запуске.
На macOS разрешите запуск скачанного приложения в System Settings → Privacy & Security.

В релизах есть файл `SHA256SUMS.txt`. На Linux проверьте скачанные пакеты из папки с ними:

```bash
sha256sum -c --ignore-missing SHA256SUMS.txt
```

macOS Get Info показывает версию бандла `1.x.y` из-за требований упаковки.
Настоящая версия Skerry указана на экране About в приложении.

</details>

Linux, Windows, macOS и Android активно развиваются. iOS/iPadOS отложены; таргета iOS в проекте пока нет.

<a id="privacy"></a>

## Хранилище и синхронизация

- **Локальное хранилище:** Argon2id и XChaCha20-Poly1305 защищают ключи, пароли, учётные записи и сертификаты. Мастер-пароль и ключи шифрования в открытом виде остаются на устройстве.
- **Опциональная синхронизация:** собственный сервер хранит зашифрованные записи, обёрнутые ключи и метаданные. Архитектура рассчитана на то, что сервер не может расшифровать содержимое хранилища.
- **Устройства и совместный доступ:** сопряжение по QR-коду и обмен хостами, сниппетами и ранбуками со сквозным шифрованием.

Развёртывание, TLS и резервное копирование описаны в [руководстве sync-сервера](server/README.ru.md).
Для недоверенных сетей используйте HTTPS; серверу нужен обратный прокси с TLS.

**Криптография не проходила независимый аудит.** Ограничения и приватное сообщение
об уязвимостях описаны в [политике безопасности и модели угроз](SECURITY.md).

## AI и приватность

AI поддерживает локальные GGUF-модели через llama.cpp и сервисы с OpenAI-совместимым API.
Для внешнего сервиса используется ваш API-ключ. **Политика хоста по умолчанию разрешает только локальные модели.** Облачные запросы
идут напрямую к выбранному провайдеру, если политика хоста разрешает их.

**Ассистент сессии по умолчанию прикладывает вывод последних двух команд.** Выберите ноль
в настройке контекста, чтобы его не отправлять. Предложенные команды требуют подтверждения
перед выполнением; рискованные команды — дополнительного подтверждения.

<details>
<summary>Политики хоста и данные, отправляемые модели</summary>

| Политика | Поведение |
| :--- | :--- |
| **Strict** · по умолчанию | Только локальная модель, с удалением распознаваемых секретов. |
| **Balanced** | Облако разрешено; распознаваемые секреты удаляются перед отправкой. |
| **Permissive** | Облако разрешено без удаления секретов. |
| **Off** | AI отключён для этого хоста. |

- **Ассистент сессии:** вопрос, история диалога и выбранное количество последних результатов команд.
- **Explain:** выделенный вывод или последний блок команды.
- **Списки хостов и записи хранилища:** автоматически не прикладываются.
- **Удаление секретов:** работает по шаблонам и не даёт гарантий. Quick-chat удаляет распознаваемые секреты всегда, включая локальный режим.

</details>

<a id="build"></a>

## Разработка

В основе — **Kotlin Multiplatform, Compose Multiplatform и Ktor**. Для сборки клиента нужны **JDK 21 и Android SDK**, даже для десктопа. Укажите `ANDROID_HOME` или `sdk.dir` в `local.properties`.

```bash
./gradlew :composeApp:run               # Desktop
./gradlew :androidApp:installDebug      # Android
./gradlew :server:run -PserverOnly      # Server
```

Для сборки только сервера Android SDK не нужен. Настройте `SKERRY_JWT_SECRET` по [инструкции запуска сервера](server/README.ru.md#быстрый-старт).

[Участие в разработке](CONTRIBUTING.md) · [Процесс и проверки](docs/development-process.md) · [Версии зависимостей](gradle/libs.versions.toml) · [Ошибки и предложения](https://github.com/SeCherkasov/SkerrySSH/issues/new/choose)

## Лицензия

Клиенты: **[GPL-3.0](LICENSE)**. Sync-сервер: **[AGPL-3.0](server/LICENSE)**.
Лицензии встроенных шрифтов и атрибуция — в [licenses/](licenses/README.md).
