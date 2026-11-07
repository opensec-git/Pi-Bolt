package opensec.pibolt.app

import opensec.pibolt.app.data.Host
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.session.Welcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Home's and the empty session's own logic in the redesign. */
class HomeRulesTest {
    @Test
    fun liveRowTitleIsTheNameThenTheLatestPromptThenTheProject() {
        assertEquals("Fix the depth guard", ProjectRules.focusTitle("Fix the depth guard", "something else", "calc-parser"))
        // A blank name falls through to the prompt's first non-blank line.
        assertEquals("Run the tests", ProjectRules.focusTitle("  ", "\n  Run the tests  \nand report", "calc-parser"))
        assertEquals("calc-parser", ProjectRules.focusTitle(null, "   \n ", "calc-parser"))
        assertEquals("calc-parser", ProjectRules.focusTitle(null, null, "calc-parser"))
    }

    @Test
    fun theComposerEntersAfterEveryWelcomeElement() {
        // Title 0, its line 1, suggestions from 2, then the recent prompts and the commands row, one step each.
        assertEquals(6, Welcome.composerStep(suggestions = 4, recent = false, commands = false))
        assertEquals(8, Welcome.composerStep(suggestions = 4, recent = true, commands = true))
        assertEquals(3, Welcome.composerStep(suggestions = 0, recent = true, commands = false))
        assertEquals(2, Welcome.composerStep(suggestions = 0, recent = false, commands = false))
        assertEquals(3, Welcome.RECENT)
    }
}

class ComputerRulesTest {
    private fun host(vararg endpoints: String, alias: String = "") = Host(id = "id", name = "MacBook", endpoints = endpoints.toList(), alias = alias)

    @Test
    fun connectionsReadLanTailscaleAndTunnelFromTheEndpoints() {
        assertEquals(listOf("LAN"), host("ws://192.168.1.20:7878").connections())
        assertEquals(listOf("Tailscale"), host("ws://100.101.102.103:7878").connections())
        assertEquals(listOf("Tailscale"), host("wss://mac.tail1234.ts.net/rc").connections())
        assertEquals(listOf("Tunnel"), host("wss://abc.trycloudflare.com/rc").connections())
        // 100.0.0.0/10 outside 100.64/10 is not Tailscale's range.
        assertEquals(listOf("LAN"), host("ws://100.12.0.1:7878").connections())
        // Each kind once, always in the order LAN, Tailscale, Tunnel.
        assertEquals(listOf("LAN", "Tailscale", "Tunnel"), host("wss://x.example.com", "ws://10.0.0.2:1", "ws://100.64.0.9:1", "ws://10.0.0.3:1").connections())
        assertEquals(emptyList(), host().connections())
    }

    @Test
    fun aNameGivenOnThePhoneWinsAndSurvivesTheJsonRoundTrip() {
        assertEquals("MacBook", host("ws://10.0.0.2:1").displayName)
        assertEquals("MacBook", host("ws://10.0.0.2:1", alias = "  ").displayName)
        val named = host("ws://10.0.0.2:1", alias = "Studio")
        assertEquals("Studio", named.displayName)
        assertEquals(named, Host.fromJson(named.toJson()))
        // A host saved before aliases has none.
        assertEquals("", Host.fromJson(host("ws://10.0.0.2:1").toJson())!!.alias)
        // Connecting copies the stored host, so the alias stays (SessionController upserts existing.copy(name = …)).
        assertEquals("Studio", named.copy(name = "MacBook Pro").displayName)
    }

    @Test
    fun thePillStartsAChatWhenAChatWasTheLatestThing() {
        // No project: always a chat.
        assertTrue(ProjectRules.pillDefaultsToChat(noProjects = true, lastChatAt = null, lastProjectAt = null))
        // A chat written after the last project work.
        assertTrue(ProjectRules.pillDefaultsToChat(noProjects = false, lastChatAt = 2_000, lastProjectAt = 1_000))
        assertTrue(ProjectRules.pillDefaultsToChat(noProjects = false, lastChatAt = 2_000, lastProjectAt = null))
        // A project worked in since, or no chats: the project.
        assertFalse(ProjectRules.pillDefaultsToChat(noProjects = false, lastChatAt = 1_000, lastProjectAt = 2_000))
        assertFalse(ProjectRules.pillDefaultsToChat(noProjects = false, lastChatAt = null, lastProjectAt = 2_000))
        assertFalse(ProjectRules.pillDefaultsToChat(noProjects = false, lastChatAt = 1_000, lastProjectAt = 1_000))
    }
}
