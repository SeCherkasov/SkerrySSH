package app.skerry.ui.design

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Нижняя навигация мобильного макета `docs/new/Skerry Mobile.html` — ровно 5 корневых табов
 * ([showTabs]=true). [icon] — лигатура Material Symbols (см. [Sym]), согласована с desktop-rail
 * ([RAIL]) там, где раздел совпадает (Files/Snippets/Vault). Порядок и состав — 1:1 с макетом.
 */
enum class MobileTab(val icon: String, val label: String) {
    Hosts("dns", "Hosts"),
    Files("folder_open", "Files"),
    Snippets("code_blocks", "Snippets"),
    Vault("vpn_key", "Vault"),
    More("more_horiz", "More"),
}

/**
 * Полноэкранные push-экраны поверх таб-навигации (таб-бар скрыт). В макете это `isTerminal`,
 * `isHostDetail`, `isPorts`, `isKnown`, `isTeam`: терминал и деталь хоста открываются из Hosts,
 * а Ports/Known/Team — из таба More. Контент экранов наполняется в следующих слайсах.
 */
enum class MobileRoute { Terminal, HostDetail, Ports, Known, Team }

/**
 * Состояние мобильного макета `docs/new/Skerry Mobile.html` — навигация (текущий таб + открытый
 * push-экран) и оверлей листа New connection. Аналог [DesktopDesignState] для телефона: чисто
 * UI-состояние, мутаторы инкапсулированы (`private set`), как в
 * [app.skerry.ui.session.SessionsController]. Блокировка vault живёт в `VaultGate`, а не здесь.
 */
@Stable
class MobileDesignState {
    var tab: MobileTab by mutableStateOf(MobileTab.Hosts); private set
    var route: MobileRoute? by mutableStateOf(null); private set
    var sheetNewConn: Boolean by mutableStateOf(false); private set

    /** Таб-бар виден только на корневых экранах: push-экраны макета полноэкранные. */
    val showTabs: Boolean get() = route == null

    /** Переключить корневой таб — закрывает любой открытый push-экран. */
    fun select(t: MobileTab) {
        tab = t
        route = null
    }

    /** Открыть полноэкранный под-экран поверх текущего таба. */
    fun push(r: MobileRoute) { route = r }

    /** Вернуться с push-экрана на текущий таб (back-стрелка макета). */
    fun pop() { route = null }

    fun openNewConn() { sheetNewConn = true }
    fun closeSheet() { sheetNewConn = false }
}
