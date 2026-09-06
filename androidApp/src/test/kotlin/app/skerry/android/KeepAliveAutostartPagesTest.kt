package app.skerry.android

import app.skerry.ui.keepalive.KeepAliveVendor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The autostart table against the manifest that has to declare it.
 *
 * The two are kept by hand and fail apart silently: under Android 11 package visibility an
 * undeclared package reads as "not installed", so the system-app check refuses it, the row falls
 * back to this app's details page, and the only trace is a debug log. On a device the person taps
 * "Open" under "Autostart" and lands somewhere else.
 */
class KeepAliveAutostartPagesTest {

    @Test
    fun everyPackageTheTableNamesIsDeclaredInTheManifest() {
        val declared = declaredQueryPackages()
        for (packageName in KeepAliveAutostartPages.packages) {
            assertTrue(
                packageName in declared,
                "$packageName is opened by the autostart row but missing from the manifest <queries>",
            )
        }
    }

    /** The other direction: a package left in `<queries>` after the table dropped it. */
    @Test
    fun theManifestDeclaresNothingTheTableNoLongerOpens() {
        assertEquals(KeepAliveAutostartPages.packages, declaredQueryPackages())
    }

    /**
     * What the screen promises and what the table can deliver are the same set. A family marked as
     * having an autostart page with no component behind it draws a row whose button can only open
     * the app details page.
     */
    @Test
    fun aFamilyHasComponentsExactlyWhenItAdvertisesAPage() {
        for (vendor in KeepAliveVendor.entries) {
            assertEquals(
                vendor.hasAutostartPage,
                KeepAliveAutostartPages.forVendor(vendor).isNotEmpty(),
                "$vendor advertises hasAutostartPage=${vendor.hasAutostartPage} with a table that disagrees",
            )
        }
    }

    private fun declaredQueryPackages(): Set<String> {
        val manifest = sequenceOf(File("src/main/AndroidManifest.xml"), File("androidApp/src/main/AndroidManifest.xml"))
            .firstOrNull { it.exists() }
        checkNotNull(manifest) { "AndroidManifest.xml not found from ${File(".").absolutePath}" }
        val queries = QUERIES.find(manifest.readText())?.value.orEmpty()
        return PACKAGE.findAll(queries).map { it.groupValues[1] }.toSet()
    }

    private companion object {
        val QUERIES = Regex("<queries>.*?</queries>", RegexOption.DOT_MATCHES_ALL)
        val PACKAGE = Regex("""<package\s+android:name="([^"]+)"""")
    }
}
