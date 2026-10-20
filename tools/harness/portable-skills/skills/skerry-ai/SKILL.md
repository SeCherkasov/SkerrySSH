---
name: skerry-ai
description: "Change Skerry AI routing, redaction, cloud streaming, local isolated inference, model downloads or command confirmation. Use for AI product code, not as a generic coding-agent delegation skill."
---

# Skerry: AI policies и локальные модели

## Разделить обязанности

Shared ai: AiPolicy/AiPolicyDecision, AiRouter/AiEndpoint, AiSettings/OpenAiConfig, AiProvider, SecretRedactor, CommandRisk. UI ai: AiStreamRunner/AiReplyParser, assistant controllers/surfaces, model picker/cache. JVM OpenAiProvider/ModelDownloader/LlamatikRuntime; local IPC contract LlmHostProtocol/Link/IsolatedLlmRuntime. Desktop host ProcessLlmHostLauncher/LlmHostMain, Android host ServiceLlmHostLauncher/LlmHostService.

Это runtime AI продукта. Просьба использовать GLM через OpenCode для разработки относится к другому скиллу (`skerry-agent-orchestration`) и не меняет product AI settings.

## Политики

| Host policy | Поведение snapshot |
|---|---|
| Strict (default) | Только local model, secrets redacted |
| Balanced | Cloud разрешён, secrets redacted |
| Permissive | Cloud разрешён без redaction, только по текущей policy |
| Off | AI выключен для host |

Global provider OFF блокирует любой маршрут. Strict с выбранным cloud всё равно требует local ready model. Разрешённый cloud без ключа и device без скачанной модели — **blocked reason**, не silent fallback. Host Off обычно отсекается controller до router; не потерять эту проверку при новом caller.

Execution confirmation обязательна независимо от policy. AI output untrusted: command parsing/display и ProductionGuard остаются на пути исполнения. Не превращать streaming fragment в auto-execute. Нельзя отправлять terminal context наружу до применения policy/redaction.

## External provider

OpenAI-compatible HTTP requests через bounded untrustedHttpClient/ResponseSizeLimit. Проверить URL/base path/auth/request shape, streaming parser на arbitrary chunk boundaries, limits на frames/line/message/total response и cancellation. Redaction применяется к outbound prompt/context до network side effect, не только при rendering логов.

Проверять disconnect до/после частичного ответа, malformed SSE/JSON, long stream, refused auth/rate limit, retry boundaries и partial answer state. UI busy сбрасывается в finally. Log/exception message не содержит Authorization/API key/raw secret prompt.

Existing OpenAiProviderTest/catalog stream tests используют MockEngine — это реальная request/parser проверка, не proof текущего внешнего API или тарифа. Real provider call делать лишь в авторизованной области с конкретным dev credential, без вывода значения.

## Local model и isolation

Llama.cpp может abort/SIGILL вместо Kotlin exception. Поэтому inference вынесен в отдельный desktop process / Android :llm service. Model crash должен завершить один ответ/host, а не SSH sessions и приложение. Не переносить native generation обратно в UI process ради короткого benchmark.

Desktop packaged launcher перезапускает приложение с host flag до AWT/Skia/vault init; jlink runtime может не содержать bin/java. Читать LlmHostCommandLine scrubEnvironment и tests: _JPACKAGE_LAUNCHER/launch markers не должны ломать дочерний host. Android service non-exported, process :llm, bind/death/reconnect lifecycle отдельный.

Model download: catalog id/path/size/hash и installed marker по текущему LocalModelStore; bounded network/disk, temp+atomic completion, cancel/partial file не должен считаться installed. Path не брать из hostile id без validation. GGUF weights большие, автоматом не скачивать их для любой code task.

## Dependency hold

В каталоге snapshot Llamatik 1.9.0 удерживается из-за native ISA/AVX-512 рисков более новых версий. Комментарии документируют отдельно Linux/Windows evidence и стоимость hold. **Не обновлять по одному номеру версии.** Перед bump читать текущие комментарии, dependabot ignores, LlamatikNativeIsaTest, native disassembly result и реальные запуски на нужных CPU/OS. Отсутствие запрещённой инструкции в одном binary не доказывает всё семейство архитектур.

Реальная интеграция `LlamatikRuntimeIntegrationTest` активируется SKERRY_LOCAL_AI_MODEL=/path/to/dev.gguf; обычный skipped run не доказывает generation. Запуск local inference — тяжёлая нагрузка под отдельным process-group cap; модель должна помещаться в согласованный лимит. Не повышать 3 GiB молча, подбирать меньшую fixture/разделять проверки.

## Проверки и пользовательский сценарий

Unit: policy truth table, routing block reasons, redactor boundaries, parser hostile text, confirmation invariant. Integration: bounded provider stream/cancel, IPC death, local model load/generation при наличии разрешённой fixture. UI: обе формы settings, disabled/error/recovery/download states, безопасное отображение команды и refused execution.

User path: host Strict → cloud configured/local absent → понятный block без outbound call; local ready → streaming answer → подтверждение команды; Balanced → redacted request → cancellation/retry; host Off/global Off. Проверить host/session switch, чтобы ответ/command не применился к другой вкладке. Итог явно разделяет fake provider, real cloud, real local binary и Android service evidence.
