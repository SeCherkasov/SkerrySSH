---
name: skerry-release-github
description: "Prepare or carry out authorized Skerry Issue/PR delivery, packaging, client draft release and separate server release workflows using mandatory project formats and verified artifacts."
---

# Skerry: GitHub и выпуск релизов

## Авторизация и источники

Сначала прочитать `docs/development-process.md` → GitHub descriptions и matching `.github` template. Перед drafting actual PR/Issue прочитать последние одобренные reference: PR #433/#434 и Issue #435 в SeCherkasov/SkerrySSH. Проверить current remote, branch/base/diff и user delivery authorization. Не превращать просьбу «подготовь draft» в public publish.

Commit/push/PR/merge/tag/release publication сохраняют явно разрешённые границы. Уже разрешённую цепочку выполнить до завершения, но разрешение на код не считать разрешением на release. Не bypass native hooks/gate. Сообщения людям/issue comments — только по явной задаче или отдельно разрешённому workflow.

## Pull Request

English title с поведением/проблемой, body понятен без чата. Headings в порядке:

1. `## What this does`: user-visible result, конкретный before/after example, короткие behavior bullets и relevant compatibility.
2. `## Measured improvement` только performance: условия/workload + baseline/updated/improvement table, единицы одинаковы; synthetic/live отдельно.
3. `## How to verify`: numbered user actions с observable expected results; для docs/process — соответствующий review path.
4. `## Verification`: только реально завершённые tests/build/reviews/UI/CI checks, pending как pending.
5. Последняя явная строка `Not verified live:` с concrete gaps; для чистого docs task можно честно none applicable.

Не заменять на Summary/Changes/Validation. Не открывать class names/protocol jargon. Closes #N только для полного resolution. Длинные methods/logs в labelled details, без chat chronology/agent tokens/resource tuning, если оно не нужно для measurement reproducibility. При изменении scope переписать title/body по итоговому diff.

Body сохранить UTF-8 file вне checkout и `gh pr create|edit --body-file /path/body.md`; не строить multiline shell string через JSON.stringify. После публикации прочитать title/body обратно, открыть rendered view и исправить literal \\n/таблицы/пустые sections. Если среда поддерживает attach_artifact, прикрепить каждый созданный PR к текущему chat.

## Issue

Bug: Summary, Steps to reproduce, Actual behavior, Expected behavior, Scope, checkbox Acceptance, Relevant implementation только для inspected code. Feature: Summary, Scope, checkbox Acceptance, optional inspected Relevant implementation. Versions/platform/repro unknown указывать явно. Acceptance — observable outcomes, не список действий агента. Labels брать из существующих bug/enhancement/area; не придумывать taxonomy. Не публиковать tracking issue без запроса.

## Checks и merge

До доставки gate verify complete. Проверить exact staged files, tracked tree/index match, requested commit. После push — CI build-test/CodeQL и packaging smoke по scope; успешный прошлый run на другом SHA не подтверждает текущий HEAD. При разрешённом merge дождаться checks/решить реальные failures, затем merge и разрешённая cleanup. Не удалять ветку с чужой работой и не утверждать remote cleanup по одному local status.

## Versions и packages

Читать `gradle.properties`, оба module build scripts, `.github/workflows/release.yml`, AppImage metadata, server-image.yml.

- Client versionName/versionCode — gradle.properties. AppVersion генерируется, не редактировать generated Kotlin.
- Android versionCode должен расти относительно реально выпущенных assets/stores, не просто local file. Не reuse code при повторном store release. Tag v* задаёт client versionName в workflow; workflow_dispatch может override по actual inputs.
- Server version в server/build.gradle.kts независима от client; server-v* отдельно.
- Desktop packaging: DEB/RPM/AppImage на Linux, MSI/portable ZIP на Windows, DMG на macOS; Android release APK текущего ABI. Tasks: packageDeb/packageRpm/packageAppImage/packageMsi/packagePortableZip/packageDmg, assembleRelease.
- jpackage/jlink требуют полного JDK и platform packaging dependencies. Native distribution проверить на соответствующем OS/arch; Gradle run не проверяет packaged runtime.
- Desktop ProGuard/minification остаётся disabled: reflective natives/JNA/libsodium/okio/signed BouncyCastle jars. Не включать ради размера без отдельной работы и platform evidence.
- AppImage helper pin/hash tools; arbitrary APPIMAGETOOL override может обходить hash check — читать script. Application catalog metadata соответствует реальному release/download architecture.

## Signing и native runtime

Android release env/property names в androidApp build script: SKERRY_KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD с соответствующими skerry.* properties. Нужен тот же signing identity для upgrade. Keystore/password не входят в bundle/git/log; debug не требует production key. Подпись/архитектуру/версию проверять по готовому APK.

Desktop packaged smoke: startup, crypto initialization/dev vault, SSH, clipboard/native libs и local AI host path при relevant change. Проверить не-ASCII/space install paths, если затронут loader. Packages для разных arch не заменяют runtime smoke на этих arch.

## Draft client release

Snapshot workflow: packaging PR/workflow_dispatch → artifacts без release; push v* → draft GitHub Release + packages + SHA256SUMS. Не считать это неизменным — читать current YAML перед запуском. Создание/push tag запускает внешние workflow и должно входить в авторизацию.

Проверить expected matrix из current YAML, filenames/versions/sizes/architectures/signatures, SHA256SUMS содержат все intended assets и реально сходятся, release остаётся draft. Составить notes по requested tag interval. Mobile store copy по запросу короткая на русском и только mobile-visible changes, без desktop-only деталей. Public Publish только после прямого пользовательского разрешения на этот шаг; просмотр draft не подразумевает согласие.

## Server release

server-image.yml: server changes PR/main build+healthy smoke без publish, server-v* публикует multi-arch Docker manifests/version aliases/latest по current workflow. Native runners и Docker Hub credentials не заменять QEMU без причины. Server tag может сразу публиковать образ — это иная граница, чем client draft. Перед deploy/push сверить полную авторизацию, versions/schema compatibility, stable JWT secret и данные volume. Не включать секреты в release notes.
