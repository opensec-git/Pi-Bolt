package opensec.pibolt.app

import opensec.pibolt.app.data.Host
import opensec.pibolt.protocol.backend.DisconnectReason
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.ui.settings.RemoteRows
import opensec.pibolt.app.ui.settings.SettingsGroup
import opensec.pibolt.app.ui.settings.SettingsIndex
import opensec.pibolt.app.ui.settings.thinkingLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The grouped Settings (2026-11-07): its section order, what search finds in the new sections, and row values. */
class SettingsRedesignTest {
    @Test
    fun sectionsAreInTheOwnersOrder() {
        assertEquals(
            listOf("Models & keys", "Appearance", "Agent", "Projects & runtimes", "Remote control", "GitHub", "Advanced", "About"),
            SettingsGroup.entries.map { it.title },
        )
    }

    @Test
    fun searchFindsTheRegroupedSettings() {
        assertEquals(listOf(SettingsGroup.Models), SettingsIndex.groups(SettingsIndex.visible("api key")))
        assertTrue("computers" in SettingsIndex.visible("pair")!!)
        assertTrue("computers" in SettingsIndex.visible("qr code")!!)
        assertTrue("device-key" in SettingsIndex.visible("security")!!)
        assertEquals(setOf("mcp"), SettingsIndex.visible("model context protocol"))
        assertTrue("packages" in SettingsIndex.visible("extensions")!!)
        assertEquals(setOf("tuning"), SettingsIndex.visible("backoff"))
        // The former Session display and Motion settings live in Agent and Advanced now.
        assertEquals(SettingsGroup.Agent, SettingsIndex.entries.first { it.id == "tool-preview" }.group)
        assertEquals(SettingsGroup.Advanced, SettingsIndex.entries.first { it.id == "reduce-motion" }.group)
        assertEquals(SettingsGroup.Advanced, SettingsIndex.entries.first { it.id == "notifications" }.group)
    }

    @Test
    fun thinkingLevelsReadAsWords() {
        assertEquals("Off", thinkingLabel("off"))
        assertEquals("Medium", thinkingLabel("medium"))
        assertEquals("Extra high", thinkingLabel("xhigh"))
    }

    @Test
    fun computerStatusFollowsTheLinkThenLastSeen() {
        val host = Host(id = "k", name = "Studio", endpoints = listOf("ws://10.0.0.2:7000"), lastConnectedAt = 1_000_000)
        val ago: (Long, Long) -> String = { _, _ -> "5 min. ago" }
        assertEquals("Connected", RemoteRows.status(host, LinkState.Connected("ws://10.0.0.2:7000"), 2_000_000, ago))
        assertEquals("Connecting…", RemoteRows.status(host, LinkState.Connecting, 2_000_000, ago))
        assertEquals("Connecting…", RemoteRows.status(host, LinkState.Retrying(DisconnectReason.entries.first(), null, 1000), 2_000_000, ago))
        assertEquals("Not connected", RemoteRows.status(host, LinkState.Failed(DisconnectReason.entries.first(), "x"), 2_000_000, ago))
        assertEquals("Last seen 5 min. ago", RemoteRows.status(host, null, 2_000_000, ago))
        assertEquals("Last seen just now", RemoteRows.status(host, null, 1_030_000, ago))
        assertEquals("Paired", RemoteRows.status(host.copy(lastConnectedAt = 0), null, 2_000_000, ago))
    }
}
