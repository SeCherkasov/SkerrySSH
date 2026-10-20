---
name: skerry-android
description: "Develop or verify Android-specific Skerry wiring, foreground keepalive, activity/service ownership, permissions, IME, biometrics, media codecs or USB serial behavior."
---

# Skerry: Android и жизненный цикл

## Где проходит граница

androidApp — MainActivity и app/service integration; composeApp androidMain — UI platform actual; shared androidMain — domain platform adapters. Общее UI/common controller менять в commonMain, не копировать логику в Activity. Compose mobile shell test на desktop JVM полезен для parity, но не проверяет Android lifecycle.

Прочитать androidApp/build.gradle.kts, AndroidManifest.xml, MainActivity/KeepAliveRuntime и соответствующий adapter. В snapshot minSdk 26, target 36, compile 37, release ABI arm64-v8a; актуальные числа в каталоге. Emulator должен подходить к native libs, иначе ошибка ABI маскирует product behavior.

## Lifecycle и session ownership

MainActivity singleTask, обрабатывает newIntent/session tap и config changes. AndroidSessionKeepAlive/KeepAliveRuntime/SessionKeepAliveService хранят surviving sessions; закрытие Activity не всегда означает close пользовательской session. Rotation/back/background/notification disconnect/session reconnect проверять по owner contract, а не просто onDestroy.

Foreground service type connectedDevice, разрешения FGS/CHANGE_NETWORK_STATE и notifications имеют разные обязанности. Refused POST_NOTIFICATIONS не обязательно запрещает service: читать текущий behavior. Partial wake lock opt-in, off по умолчанию; FGS не равен непрерывно работающему CPU при locked screen. Не включать wake lock пользователю принудительно ради прохождения теста.

KeepAlive notification registry/state и More → Background & lock screen должны соответствовать живым SSH/RDP/VNC sessions. Test KeepAliveRuntimeRdpTest покрывает часть ownership, но не firmware battery policy.

## Разрешения и trust

Camera для pairing QR optional: hardware not required, есть text flow. USB host serial optional, реальный USB permission lifecycle/device detach отдельно. Vendor autostart settings intents проверяют системное приложение/visibility queries; не отправлять в sideloaded impostor. Не обещать одинаковые background guarantees всем ROM.

AndroidBiometricKeyStore использует BiometricPrompt/CryptoObject и OS Keystore. Отмена, lockout, изменённый enrollment, Activity recreation и invalidated key — реальные отдельные исходы. Новая машина/эмулятор не наследует key material старого устройства; скопированные encrypted preferences не доказательство миграции.

## Input/media/native

ImeFunnel buffer reset внутри InputTransformation: цифры/двойные edits/backspace/composition не должны копиться через recomposition. Typed secrets не остаются в semantics. Проверять клавиатуры, rotation, hardware modifiers и focus возврат; desktop event simulation не имитирует весь Android IME.

RDP MediaCodecH264/YUV, AndroidAudio и VNC image decoder — actual implementations. Codec capability advertisement должен учитывать availability; software tests не доказывают hardware decode/audio output. SerialUsbBridge и local PTY helper имеют native lifecycle и ABI concerns.

Local LLM service `:llm` non-exported, separate process: crash не завершает app/session. Проверить binder death/rebind/cancellation отдельно от unit LlamatikRuntime test.

## Установка и проверка

- Сначала toolchain/SDK по `skerry-new-machine`, `adb devices` — выбрать конкретное разрешённое dev устройство при нескольких.
- Unit task `:androidApp:testDebugUnitTest` и client builds/lint выбирает gate. Установка `:androidApp:installDebug` через bounded runner; не удалять существующее приложение/данные ради подписи без отдельной задачи.
- Debug signing не требует production keystore. Release credentials — отдельный защищённый перенос; никогда не включать их в install bundle/CLI logs.
- `adb logcat` читать ограниченно по процессу/времени сценария, редактировать secrets перед артефактом; без нужды не собирать весь device log.
- Записать ОС/API, ABI, emulator/physical, keyboard, состояние permissions и battery options. Без этого «на Android проверено» слишком широко.

## Live matrix по scope

Cold start/dev vault; create/edit host; connect; ввод/paste/backspace; rotate; background/lock screen; notification tap/disconnect; network loss/recovery; app reopen; permission denial/retry; biometric cancel/retry. Для изменённой функции — конкретный RDP/SSH/SFTP/pairing/USB/media путь. Emulator proof не распространять на physical device, vendor power management, TalkBack или аппаратный decoder.
