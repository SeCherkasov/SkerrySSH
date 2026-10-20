---
name: skerry-troubleshooting
description: "Diagnose Skerry environment, Gradle, harness, network, native runtime and platform failures using concrete evidence and targeted recovery. Use when setup or verification is blocked."
---

# Skerry: диагностика проблем разработки

## Порядок

Сохранить исходную ошибку и exact command, текущие git status/branch/HEAD, relevant environment facts без secrets. Классифицировать сбой: prerequisites → compilation → test assertion → runner evidence → live platform. Не лечить всё удалением кэшей. Одна понятная гипотеза и проверка за раз; после исправления rerun необходимого stage, не весь проект без причины.

| Симптом | Что проверить и как исправлять |
|---|---|
| Unsupported class version / Java 11 / no javac | JAVA_HOME, PATH, IDE Gradle JVM; нужен полный JDK 21. Наличие Java 25 JRE не заменяет JDK 21 |
| SDK missing при desktop task | Full settings graph включает Android. ANDROID_HOME или sdk.dir, нужные platform/build-tools/licenses. serverOnly только для действительно server-only tasks |
| API 37 package недоступен через sdkmanager | Сверить каталог и текущий CI setup (в snapshot есть explicit pinned SDK archive); не понижать compileSdk молча. Проверить repository/network/license error |
| Compose no DISPLAY / X server | Действующий DISPLAY или xvfb-run --auto-servernum; WAYLAND_DISPLAY не всегда достаточен. Не считать skipped UI suite успехом |
| systemd user bus unavailable | Реальные XDG_RUNTIME_DIR/DBUS_SESSION_BUS_ADDRESS активной сессии, systemctl --user; не hardcode UID/старый socket. Не запускать unbounded fallback |
| Build OOM/cgroup kill/GC thrash | MemAvailable/swap/competing builds и общий cgroup. Fresh JVM, меньше stage/filter/данных; in-process compiler и low workers. Не повышать 3 GiB |
| Another build lock / disappeared daemon | Не запускать Gradle одновременно с gate/IDE/другим worktree. Проверить owner PID/activity. Не глобальный pkill java/kotlin |
| run exit 1 при зелёных tests | status --json/next_actions: могут быть review/RED/finding debts. --build-only не закрывает final local debt |
| RED refused | Assertion действительно воспроизводит bug? Нет ли compilation error/empty glob/all skipped/изменённого test hash? Исправить test и честно повторить RED |
| JUnit missing/stale/all skipped | Leaf task name и XML, exact command/filter, актуальный run. Не подкладывать старые XML и не импортировать чужие receipts |
| Review token/snapshot mismatch | Новый launch до actual review, correct reviewer/token/delta. Не переписывать report ради принятия устаревшего анализа |
| Review round limit | Два completed rounds исчерпаны; further drift owed. Нужна прямая инструкция пользователя для extra round, нельзя автоматом exempt |
| Hook rejects staged tree/push | Проверить index vs tracked worktree/verified HEAD/clean tree/main. Исправить причину, не --no-verify |
| ECC missing/unresolved | Current catalogue/ECC_PLUGIN_ROOT/--ecc-root. Несколько cached versions требуют выбора. Report unavailable, не fabricated completion/implicit install |
| SSH Permission denied | Current SSH_AUTH_SOCK/ssh-add -l/user config и remote. Не выводить private key/token. DNS error до auth не свидетельствует о ключе |
| DNS Operation not permitted / resolution failure | Один раз проверить DNS/network policy; дать конкретное восстановление для текущей среды. Не повторять SSH/HTTPS probes, которые не доходят до auth |
| Gradle dependency resolution failure | Exact coordinate/repository/TLS/network vs missing artifact, scoped repo rules. Не добавлять случайный mirror и не менять версию без анализа |
| Packaged app падает, Gradle run работает | jlink modules, disabled minification, natives/JNA/libsodium/signed jars, install path spaces/non-ASCII, OS/ABI. Проверить именно package |
| Clipboard работает только внутри app | Wayland native buffer vs AWT, wl-copy/wl-paste, SystemClipboard path. Refused operation не равно empty/success |
| RDP settings «не применились» | Requested/persisted/negotiated отдельно; новая session vs live control; canvas background vs server wallpaper/DPI. Read actual profile/capabilities |
| Remote session висит после server close | Read/Close watcher ownership, не ждать следующей картинки; stale session callbacks/reconnect lifetime |
| Android ABI/install signature mismatch | Device ABI vs native artifacts; debug/release signing identity. Не uninstall с потерей данных без разрешённой задачи |
| Android session теряется под lock | FGS vs CPU suspend, opt-in wake lock, notifications/vendor battery policy. Не обещать универсальную ROM гарантию |
| Local AI SIGILL/abort | Native ISA/dependency hold, isolated host, actual CPU/OS. Не переносить inference в app process и не обновлять Llamatik наугад |
| Server не стартует с default JWT | guardConfig требует strong secret или явный SKERRY_DEV=1 для localhost; не отключать guard |
| SQLite readonly в Docker | /data ownership uid/gid 999 по текущему Dockerfile; volume permissions/DB path. Не chmod 777 |
| Sync после смены сервера теряет записи | ServerLink/scoped cursor identity, old callbacks/reconcile, authentication metadata. Не reset все vault данные вместо исправления scope |
| OpenCode empty stdout | Session status/messages API + actual diff/checks. Не объявлять executor failed/success только по exit code |

## Минимальные артефакты

Error class, command, stage log path, relevant few lines, environment/toolchain version, expected/actual и следующий наблюдаемый check. Отчёт вне repo, secrets редактировать. В `.git` worktree path может быть indirection — разрешать git-dir через Git. Не публиковать целый env/logcat/hprof/vault dump ради диагноза.

## Recovery boundaries

Можно повторить отменённый read-only check, восстановить launch-local environment, сузить test workload и исправить воспроизводимый defect в разрешённом scope. Нельзя скрывать ошибку receipts/test exclusions/rebaseline, менять global resource policy, удалять пользовательские данные/ключи или переключать исполнителя вопреки прямой инструкции. Если нужен внешний доступ/устройство/разрешение на действие — сохранить готовую работу и назвать конкретно, чего не хватает.
