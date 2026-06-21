package app.skerry.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import app.skerry.shared.host.Host
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.session.SessionsController

/**
 * Фича-флаги отображения дизайн-слоя. Поставляются параметром в [DesktopDesignApp] и доступны
 * любому composable через [LocalFeatures]. Незавершённые фичи прячутся за флагом (а не удаляются
 * из макета), чтобы вернуть их одним переключателем, когда бэкенд готов.
 *
 * [ai] — AI-ассистент (Phase 2 / MVP2): нижний AI-bar, suggestion-карточки в терминале, выбор
 * AI-политики в New connection и таб «AI» в настройках. По умолчанию выключен — в MVP1 этих
 * элементов в UI нет, реализация остаётся заглушкой до MVP2.
 */
@Immutable
data class FeatureFlags(
    val ai: Boolean = false,
)

/** Текущие фича-флаги; дефолт — всё незавершённое выключено (мок-путь/превью и MVP1). */
val LocalFeatures: ProvidableCompositionLocal<FeatureFlags> = staticCompositionLocalOf { FeatureFlags() }

/**
 * Живые бэкенды, подаваемые в дизайн-слой через CompositionLocal (тем же приёмом, что [LocalFonts]) —
 * чтобы не протаскивать контроллеры параметрами через каждый composable макета. `null` означает
 * мок-путь (офскрин-рендер/превью): composable рисует статичные данные [DesktopMockData].
 *
 * [DesktopDesignApp] поставляет их за гейтом vault; по мере разводки бэкендов сюда добавятся
 * SFTP/форвардинг следующими слайсами.
 */
val LocalHosts: ProvidableCompositionLocal<HostManagerController?> = staticCompositionLocalOf { null }

/**
 * Менеджер открытых сессий (вкладки + живые соединения). `null` — мок-путь без бэкенда соединений:
 * титулбар и терминал рисуют статичные данные макета.
 */
val LocalSessions: ProvidableCompositionLocal<SessionsController?> = staticCompositionLocalOf { null }

/**
 * Действие «подключиться к хосту»: резолвит секрет (identity из vault или запрос пароля) и открывает
 * сессию. Поставляется корнем chrome ([DesktopDesignApp]); дефолт — no-op (мок-путь/превью).
 */
val LocalConnectHost: ProvidableCompositionLocal<(Host) -> Unit> = staticCompositionLocalOf { {} }
