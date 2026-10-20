---
name: skerry-connections-files
description: "Work on Skerry SSH routing, SFTP/files, tunnels, additional terminal transports, snippets, runbooks and container sessions. Use for connection/transfer/command execution behavior."
---

# Skerry: подключения, файлы и операции

## Подключения

Shared common contracts: ssh/SshTransport, ShellChannel, Host/HostStore, HostTrust, KnownHosts, PortForwarding. JVM implementations в shared jvmSharedMain: SshjTransport, SshjConnection/SshjShellChannel/SshjForwards, RoutingTransport, Socks5, SharedConnectionPool. Platform local shell/serial actual — desktopMain/androidMain.

Host identity и authentication не сводятся к socket connect. Сначала понять transport route: прямой SSH, proxy/jump host, stream-only, container exec, remote desktop. Проследить каждый hop, trust decision, credential/key resolver, cancellation и ownership connection pool. `RoutingTransport` — существующая точка маршрутизации; не обходить её при новой user action.

PromptQueue и HostTrustPromptController/KeyboardInteractivePromptController разделяют конкурентные вопросы. Ответ должен относиться к своему connection request, timeout начинается с появления вопроса. TOFU mismatch нельзя превращать в «accept all» ради успешного теста.

## SFTP и local/remote files

`shared/sftp/SftpClient` → jvmShared SshjSftpClient; `shared/files` FileBrowser/LocalFileBrowser/SftpFileBrowser; `composeApp/ui/files` TransferQueue/TransferRunner; desktop/mobile SFTP surfaces. Existing queue strip и mobile queue надо использовать, не добавлять отдельный progress state на каждый layout.

- Path/name/listing, remote symlink, server attributes и lengths — untrusted. Не доверять отображаемому имени как безопасному локальному пути. TreeWalkLimit ограничивает обход.
- Overwrite confirmation хранит snapshot source/destination/host/path. Смена панели или текущего каталога после показа не должна перенаправлять action.
- Cancellation активного transfer должна закрыть/прервать операцию по контракту, не только скрыть progress. Проверять next queued item и независимость transfer от другой session.
- Progress cadence можно ограничивать без потери final state/error и корректного cancellation. Request/transfer window имеет bounds; не заменять bounded requests unlimited async ради throughput.
- Поддерживать sorting/filtering/keyboard на обоих layouts. Отказ permission/disk full/disconnect должен давать понятный outcome и способ повторить в корректном состоянии.

## Tunnels и pool

Model/store в shared/tunnel, state ForwardState в ssh. Различать owning SSH connection и borrowing pooled jump connection. Close/reconnect одной вкладки не должен уничтожить чужой tunnel или оставить worker/socket. Проверять port conflict, cancelled start, remote failure и stop during reconnect. Forward target/ports валидировать до side effects.

## Telnet, serial, local shell, Mosh, jump shell

StreamOnlyConnection и StreamShellChannel — общий механизм «только поток» и blocking read loop, не ещё одна копия SSH-specific lifecycle. TelnetCodec владеет telnet protocol; serial desktop jSerialComm и Android USB-OTG имеют разные permission/lifecycle. PTY desktop pty4j, Android native helper: packaged native behavior отдельно от unit JVM proof.

Mosh разделён на common key/framing/timing/wire и JVM codec/transport/bootstrap. Не путать SSH bootstrap success с рабочим UDP session/recovery. Jump shell — prompt launch/watch semantics, не обычный SSH TCP hop. Новые fallback должны действительно завершать business operation; retry/queue/delayed recovery/cache — отдельные режимы и так должны называться.

## Snippets, runbooks, containers

- shared snippet: templates, vault values, captured run environment; runbook: model/script/marker/history. UI managers/runners и execution policy — existing path.
- Команду подтверждать в полном безопасном CommandQuote с ClippedNotice; row CommandLine только отображает summary. Подставляемые values/notes/labels — untrusted. Имена групп normalizeGroup, tags через Tags, секреты не выводить в plaintext preferences/logs.
- ProductionGuard/CommandRisk и AI policy не обходить при новой execute shortcut. Runbook step result связывать с correct session/mark/generation, не угадывать по произвольной output строке.
- ContainerSpec/ContainerCommands/ContainerListing/ContainerTransport строят docker/k8s exec путь. Shell escaping и boundaries аргументов проверять на quotes/newlines/hostile container names; не конкатенировать untrusted command segments без существующего builder.

## Проверки

SSH: embedded SSHD existing tests + known host mismatch/cert/2FA/cancel/proxy/jump scenarios по scope. SFTP: SshjSftpClientTest, request/transfer window/cancellation tests, TransferRunner/Queue tests и desktop/mobile queue UI. Реальный dev server отдельно для файловых прав, latency, symlink/recursive copy, больших очередей и reconnect.

Для новой команды проверить полный action path от UI до write + отказ policy + confirmation race. Для transport — happy path, read EOF, close during operation, cancelled connect и повторный запуск. Прямые Kotlin/JVM tests под лимитом; security reviewer обязателен по planner для protocol/SSH/SFTP paths.
