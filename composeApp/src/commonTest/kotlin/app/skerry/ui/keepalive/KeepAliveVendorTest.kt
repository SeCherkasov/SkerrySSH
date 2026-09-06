package app.skerry.ui.keepalive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The one piece of the keep-alive power screen that is pure logic: which ROM family a
 * `Build.MANUFACTURER` string belongs to. It decides both what the steps say and which system page
 * a step opens, and every device reports it differently — a Redmi says "Xiaomi", a POCO sometimes
 * says "POCO", a HONOR says "HONOR" in caps.
 */
class KeepAliveVendorTest {

    @Test
    fun xiaomiFamilyCoversRedmiAndPoco() {
        assertEquals(KeepAliveVendor.Xiaomi, keepAliveVendorOf("Xiaomi"))
        assertEquals(KeepAliveVendor.Xiaomi, keepAliveVendorOf("Redmi"))
        assertEquals(KeepAliveVendor.Xiaomi, keepAliveVendorOf("POCO"))
    }

    @Test
    fun huaweiFamilyCoversHonor() {
        assertEquals(KeepAliveVendor.Huawei, keepAliveVendorOf("HUAWEI"))
        assertEquals(KeepAliveVendor.Huawei, keepAliveVendorOf("HONOR"))
    }

    @Test
    fun oppoFamilyCoversOnePlusAndRealme() {
        assertEquals(KeepAliveVendor.Oppo, keepAliveVendorOf("OPPO"))
        assertEquals(KeepAliveVendor.Oppo, keepAliveVendorOf("OnePlus"))
        assertEquals(KeepAliveVendor.Oppo, keepAliveVendorOf("realme"))
    }

    @Test
    fun vivoFamilyCoversIqoo() {
        assertEquals(KeepAliveVendor.Vivo, keepAliveVendorOf("vivo"))
        assertEquals(KeepAliveVendor.Vivo, keepAliveVendorOf("iQOO"))
    }

    @Test
    fun samsungIsNamedButHasNoAutostartPage() {
        assertEquals(KeepAliveVendor.Samsung, keepAliveVendorOf("samsung"))
        assertFalse(KeepAliveVendor.Samsung.hasAutostartPage)
    }

    /** A name the list doesn't know, and an empty one (an emulator reports neither) — no page to open. */
    @Test
    fun unknownAndBlankFallBackToOther() {
        assertEquals(KeepAliveVendor.Other, keepAliveVendorOf("Google"))
        assertEquals(KeepAliveVendor.Other, keepAliveVendorOf(""))
        assertEquals(KeepAliveVendor.Other, keepAliveVendorOf("   "))
        assertFalse(KeepAliveVendor.Other.hasAutostartPage)
    }

    /**
     * The name is matched inside the string, not against it: firmware writes "Xiaomi Communications"
     * and "vivo Mobile Communication" as often as the bare brand.
     */
    @Test
    fun matchesTheBrandInsideALongerName() {
        assertEquals(KeepAliveVendor.Xiaomi, keepAliveVendorOf("Xiaomi Communications Co., Ltd."))
        assertEquals(KeepAliveVendor.Vivo, keepAliveVendorOf("vivo Mobile Communication Co., Ltd."))
    }

}
