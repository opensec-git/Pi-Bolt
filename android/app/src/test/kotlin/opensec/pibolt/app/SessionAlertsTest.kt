package opensec.pibolt.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import opensec.pibolt.app.ui.session.features2.SessionAlerts
import opensec.pibolt.app.ui.session.features2.SessionAlerts.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// PR #258 review: Navigate cleared the target before opening its session, which cancelled the open halfway.
class SessionAlertsTest {
    private val target = Target.Local("demo", "/s/one.jsonl")

    @Test
    fun onlyThisAppsProjectsAndSessionFilesAreOpened() {
        // MainActivity is exported: `am start --es opensec.pibolt.session.project ..` from any app opened a session with
        // every tool in HOME (projects/..), and the session extra could name any file.
        val root = kotlin.io.path.createTempDirectory("alerts").toFile()
        try {
            val projects = java.io.File(root, "home/projects").apply { mkdirs() }
            java.io.File(projects, "demo").mkdirs()
            val sessions = java.io.File(root, "agent/sessions").apply { mkdirs() }
            val chats = java.io.File(root, "agent/chats").apply { mkdirs() }
            val roots = listOf(sessions, chats)
            val saved = java.io.File(java.io.File(sessions, "--demo--").apply { mkdirs() }, "one.jsonl").apply { writeText("{}\n") }.path
            val chat = java.io.File(chats, "c.jsonl").apply { writeText("{}\n") }.path
            val auth = java.io.File(root, "agent/auth.json").apply { writeText("{}") }.path

            assertEquals(Target.Local("demo", saved), SessionAlerts.safe(Target.Local("demo", saved), projects, roots))
            assertEquals(Target.Local("demo", null), SessionAlerts.safe(Target.Local("demo", null), projects, roots))
            assertEquals(Target.Chat(chat), SessionAlerts.safe(Target.Chat(chat), projects, roots))
            assertEquals(Target.Remote, SessionAlerts.safe(Target.Remote, projects, roots))
            for (bad in listOf("..", ".", "../home", "demo/../..", "", " ", ".chats", "a\u0000b")) {
                assertNull(SessionAlerts.safe(Target.Local(bad, null), projects, roots), "project \"$bad\"")
            }
            // A file outside the session folders is dropped; the project (or the live chat) still opens.
            assertEquals(Target.Local("demo", null), SessionAlerts.safe(Target.Local("demo", auth), projects, roots))
            assertEquals(Target.Local("demo", null), SessionAlerts.safe(Target.Local("demo", "$sessions/../auth.json"), projects, roots))
            assertEquals(Target.Chat(null), SessionAlerts.safe(Target.Chat(auth), projects, roots))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun targetStaysPendingUntilHandled() = runBlocking {
        val pending = MutableStateFlow<Target?>(target)
        val opening = CompletableDeferred<Unit>()
        val handled = mutableListOf<Target>()
        val job = launch {
            SessionAlerts.drain(pending) {
                opening.await()
                handled += it
            }
        }
        yield()
        assertEquals(target, pending.value, "still pending while the session opens")
        opening.complete(Unit)
        while (pending.value != null) yield()
        assertEquals(listOf<Target>(target), handled)
        job.cancel()
    }

    @Test
    fun cancelledMidOpenIsHandledByTheNextCollector() = runBlocking {
        val pending = MutableStateFlow<Target?>(target)
        val first = launch { SessionAlerts.drain(pending) { CompletableDeferred<Unit>().await() } }
        yield()
        first.cancel()
        assertEquals(target, pending.value)

        val handled = CompletableDeferred<Target>()
        val second = launch { SessionAlerts.drain(pending) { handled.complete(it) } }
        assertEquals(target, handled.await())
        while (pending.value != null) yield()
        second.cancel()
    }

    @Test
    fun aNewerTargetIsNotCleared() = runBlocking {
        val pending = MutableStateFlow<Target?>(target)
        val newer = Target.Remote
        val seen = mutableListOf<Target>()
        val job = launch {
            SessionAlerts.drain(pending) {
                seen += it
                if (it == target) pending.value = newer
            }
        }
        while (seen.size < 2) yield()
        assertEquals(listOf(target, newer), seen)
        while (pending.value != null) yield()
        assertNull(pending.value)
        job.cancel()
    }
}
