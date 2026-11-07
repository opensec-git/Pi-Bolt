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
