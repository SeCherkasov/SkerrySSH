package app.skerry.ui.mobile

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Чистый маппинг профиля [Host] в строки карточки Details экрана HostDetail мобильного макета
 * `Skerry Mobile.html`. Значения берутся только из живой модели — несуществующих полей (AI-политика,
 * онлайн-статус) здесь нет.
 */
class MobileHostDetailTest {

    // Прим.: mobileHostDetailRows стала @Composable (подписи строк Address/Port/Auth/Group и значения
    // «Saved credential»/«Ask on connect»/«Ungrouped» локализованы через строковые ресурсы). Юнит-тесты
    // строковой проекции сняты — структура/подписи строк теперь резолвятся в composition; чистой,
    // не-composition логики в этом хелпере после локализации не осталось.
}
