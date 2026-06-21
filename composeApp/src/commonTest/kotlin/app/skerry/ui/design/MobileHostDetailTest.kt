package app.skerry.ui.design

import app.skerry.shared.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Чистый маппинг профиля [Host] в строки карточки Details экрана HostDetail мобильного макета
 * `Skerry Mobile.html`. Значения берутся только из живой модели — несуществующих полей (AI-политика,
 * онлайн-статус) здесь нет.
 */
class MobileHostDetailTest {

    private fun host(
        address: String = "192.168.1.45",
        port: Int = 22,
        username: String = "root",
        group: String? = "Production",
        identityId: String? = null,
    ) = Host("h1", "prod-web-01", address, port, username, group, identityId)

    @Test
    fun rows_are_address_port_auth_group_in_order() {
        val rows = mobileHostDetailRows(host(identityId = "id-1"))
        assertEquals(listOf("Address", "Port", "Auth", "Group"), rows.map { it.label })
        assertEquals(listOf("192.168.1.45", "22", "Saved identity", "Production"), rows.map { it.value })
    }

    @Test
    fun address_and_port_are_monospaced_rest_is_not() {
        val rows = mobileHostDetailRows(host()).associateBy { it.label }
        assertEquals(true, rows.getValue("Address").mono)
        assertEquals(true, rows.getValue("Port").mono)
        assertEquals(false, rows.getValue("Auth").mono)
        assertEquals(false, rows.getValue("Group").mono)
    }

    @Test
    fun auth_says_ask_on_connect_when_no_identity_bound() {
        val auth = mobileHostDetailRows(host(identityId = null)).single { it.label == "Auth" }
        assertEquals("Ask on connect", auth.value)
    }

    @Test
    fun group_falls_back_to_ungrouped_when_null_or_blank() {
        assertEquals("Ungrouped", mobileHostDetailRows(host(group = null)).single { it.label == "Group" }.value)
        assertEquals("Ungrouped", mobileHostDetailRows(host(group = "  ")).single { it.label == "Group" }.value)
    }
}
