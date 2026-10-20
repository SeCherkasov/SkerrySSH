---
name: skerry-remote-desktop
description: "Implement or diagnose Skerry RDP/VNC protocol, framebuffer, display settings, clipboard, input, audio, video decoding and session lifecycle across desktop and Android."
---

# Skerry: RDP и VNC

## Сначала разделить слои

| Слой | Точки входа |
|---|---|
| Общий UI contract | shared graphics/RemoteDesktop.kt, RemoteFramebuffer |
| Lifecycle/controller | composeApp ui/remote/RemoteDesktopController, RemoteDesktopScreenState, RemoteDesktopLifetime |
| Запрос и defaults | RdpConnectRequest, RdpConnectDefaults, host NewConnectionFormState |
| Desktop/mobile chrome | RemoteDesktopBar/RemoteBarState, RemoteDesktopPanel, RemoteDesktopMenus/RemoteClipboardMenu |
| Рисование/viewport/input | ui/vnc/VncSurface, VncTouchSurface и связанные geometry/input helpers |
| RDP common wire/session | shared rdp/RdpTransport, RdpSpec, RdpClientSettings, RdpConnect, RdpIo, egfx/nla |
| RDP JVM socket/TLS | RdpTcpConnector/Transport, RdpVerifyingTrustManager, FileRdpCertificateStore |
| VNC common/wire | VncTransport, VncRemoteDesktop, RfbCodec/RfbStreaming/RfbEncodings |
| Platform codecs | VncCodecPorts, H264DecoderFactory, audio RemoteAudio/PcmPlayer |

Название VncSurface не означает VNC-only UI: общая remote framebuffer поверхность используется для обоих протоколов. Не дублировать screen state/lifetime ради RDP-specific field.

## Lifecycle

EOF/Close без следующего framebuffer update должен завершать экран. Reconnect создаёт новую owner session, старые jobs/clipboard/audio закрываются; поздние события старой попытки не меняют новую. View-only блокирует все формы input, включая keyboard/IME/clipboard send, по текущему capability contract. Android keepalive владеет surviving session отдельно от Activity.

При работе с disconnect исследовать RemoteDesktopLifetime/watchForClose и tests, не добавлять polling по картинке. Проверить connect-cancel-connect, lock/background/rotation, close во время prompt/resize, UI error recovery и чистый повторный reconnect.

## RDP negotiation и настройки

Persisted RdpSpec → request/defaults → RdpClientSettings → connect negotiation (GCC/Client Info/capabilities) → session/display control → UI. New field требует всей цепочки и импорт/default/backward compatibility. `.rdp` importer имеет собственный mapping (например session bpp).

Не смешивать **локальный canvas background** с Windows wallpaper, protocol color depth, render scale и remote resolution. Сервер вправе выбрать поддерживаемый color depth; показывать/обрабатывать negotiated значение, не только requested. `1f` render scale само по себе не доказывает Windows 100% DPI. Настройки, передаваемые при connect, проверять в новой сессии, если нет динамического control path.

Для bitmap проверить pixel format, row orientation, stride/padding, dimensions, resize и bounds. Framebuffer ARGB contract общий; RDP wire byte order не должен протекать в UI. 16/24/32-bit decode тестами покрывать отдельно от live evidence с сервером.

## Trust, NLA и каналы

RdpReader/Writer/Source/Sink + RdpProtocolException — стандартная bounded PDU работа. NtlmCrypto/RdpLicenseCrypto platform-injected; malformed BER/PDU/length/channel data обрабатывать до allocation. TOFU certificate fingerprint через общий HostTrust path; mismatch, hostname/certificate policy и self-signed сценарий не обходить trust-all manager.

Dynamic channels через egfx DynamicChannelHandler. Platform H264DecoderFactory сообщает available: advertise AVC только когда реально доступен decoder. Android MediaCodecH264 и desktop FfmpegH264/Y4mFrameReader отвечают за platform frames, wire/4:4:4 assembly — common codec. Codec unit success не доказывает установленный ffmpeg, аппаратный decoder или remote server capabilities.

## Clipboard, input и audio

Clipboard bridge использует общий SystemClipboard и existing RemoteClipboardMenu; отказ native read/write не выдавать за empty/success. Remote clipboard text untrusted, ограничивать/санитизировать отображение, сохранять различие preview и полного содержимого. Directionality/capabilities и view-only проверять по contract.

Coordinates: local canvas/viewport/zoom → remote pixels; отрицательные/outside positions, resize, pan, touch gestures. Keyboard: RDP scancode vs VNC keysym — отдельные mapping; modifier release, stuck keys после focus loss, IME funnel и rotation. Server-supplied text не может перехватить local shortcut semantics.

Audio output через PcmPlayer/PcmSinkOpener; bounded buffers, format change/reopen, output-device switch, close/reconnect. Не считать наличие audio packets доказательством слышимого звука на устройстве.

## Проверки

Сначала common codecs/negotiation/lifecycle state tests; JVM TLS/socket tests; Compose viewport/input/background/clipboard tests; Android KeepAliveRuntimeRdpTest по ownership. В live matrix фиксировать ОС/версию remote host, session depth/capabilities, renderer и client device.

User path: создать RDP/VNC host → изменить display settings → подключиться → resize/scale/background → keyboard/pointer/clipboard/audio → disconnect извне → reconnect → background/rotation на Android. При изменении color negotiation проверить реальный remote server в нужных depths; синтетический decoder proof не выдавать за live 16/24-bit coverage. Различать fake/software renderer, desktop GPU и physical Android. Протокольные задачи требуют security review по плану.
