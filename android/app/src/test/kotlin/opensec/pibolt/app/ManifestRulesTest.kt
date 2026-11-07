package opensec.pibolt.app

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rules for AndroidManifest.xml that a build does not check. */
class ManifestRulesTest {
    private val manifest = File("src/main/AndroidManifest.xml")
    private val android = "http://schemas.android.com/apk/res/android"

    private fun components(): List<Pair<String, Element>> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        return listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { tag to nodes.item(it) as Element }
        }
    }

    @Test
    fun componentClassesAreDeclaredOnce() {
        // Two <provider>s of androidx.core.content.FileProvider left the update APK's authority without its read grant, so
        // the package installer could not open it and every in-app update ended in "App not installed".
        val names = components().map { (_, e) -> e.getAttributeNS(android, "name") }
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "component classes declared more than once: $duplicates")
    }

    @Test
    fun theFileProviderServesUpdatesExportsAndDiagrams() {
        val providers = components().filter { (tag, e) -> tag == "provider" && e.getAttributeNS(android, "name") == "androidx.core.content.FileProvider" }
        assertEquals(1, providers.size)
        val (_, provider) = providers.single()
        val authorities = provider.getAttributeNS(android, "authorities").split(';').toSet()
        assertEquals(setOf("\${applicationId}.exports", "\${applicationId}.updates", "\${applicationId}.diagrams"), authorities)
        assertEquals("true", provider.getAttributeNS(android, "grantUriPermissions"))
        val paths = File("src/main/res/xml/file_provider_paths.xml").readText()
        for (dir in listOf("exports/", "updates/", "diagrams/")) assertTrue("path=\"$dir\"" in paths, dir)
    }
}
