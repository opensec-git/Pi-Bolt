package opensec.pibolt.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.backend.ConnectionState
import opensec.pibolt.protocol.backend.DisconnectReason
import opensec.pibolt.protocol.backend.EngineLauncher
import opensec.pibolt.protocol.backend.LocalBackend
import opensec.pibolt.protocol.backend.LocalConfig
import opensec.pibolt.protocol.backend.SessionTarget
import opensec.pibolt.protocol.rpc.RpcClient
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.UserItem
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalBackendTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backends = CopyOnWriteArrayList<LocalBackend>()
    private val launches = CopyOnWriteArrayList<List<String>>()
    private val tempFiles = CopyOnWriteArrayList<File>()

    @AfterTest
    fun tearDown() {
        backends.forEach { it.close() }
        scope.cancel()
        tempFiles.forEach { it.delete() }
    }

    private val launchDirs = CopyOnWriteArrayList<String>()

    private fun fakeLauncher(env: Map<String, String> = emptyMap()) = EngineLauncher { target, cwd ->
        launchDirs += cwd
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = listOf(java, "-cp", System.getProperty("java.class.path"), "opensec.pibolt.protocol.FakeEngineKt") + target.cliArgs()
        launches += target.cliArgs()
        ProcessBuilder(command).apply { environment().putAll(env) }.start()
    }

    private fun backend(
        target: SessionTarget = SessionTarget.Continue,
        env: Map<String, String> = emptyMap(),
        launcher: EngineLauncher = fakeLauncher(env),
        newest: (String) -> String? = { env["FAKE_CONTINUE_FILE"] ?: "/fake/continued.jsonl" },
        fresh: (String) -> String? = { null },
    ) = LocalBackend(LocalConfig(name = "demo", version = "1.1.1", cwd = "/projects/demo", helloTimeoutMs = 20_000), target, launcher, newest, fresh)
        .also { backends += it }

    /** Collects [LocalBackend.incoming] into a channel the test reads from. */
    private fun records(b: LocalBackend): Channel<JsonObject> {
        val channel = Channel<JsonObject>(Channel.UNLIMITED)
        scope.launch { b.incoming.collect { channel.send(it) } }
        return channel
    }

    private suspend fun Channel<JsonObject>.next(type: String): JsonObject = withTimeout(20_000) {
        while (true) {
            val m = receive()
            if (m.type == type) return@withTimeout m
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun continuedFile(): File = File.createTempFile("fake-session", ".jsonl").also { tempFiles += it }

    @Test
    fun connectEmitsAHelloSnapshotOfTheContinuedSession() = runBlocking {
        val b = backend()
        val inbox = records(b)
        assertTrue(b.connect())
        val hello = inbox.next("remote_hello")
        assertEquals("demo", hello.obj("host")?.str("name"))
        assertEquals("/projects/demo", hello.obj("host")?.str("cwd"))
        assertEquals("/fake/continued.jsonl", hello.obj("state")?.str("sessionFile"))
        assertEquals(2, hello.arr("messages")?.size)
        assertEquals("skill:fake", hello.arr("commands")?.firstOrNull()?.let { (it as JsonObject).str("name") })
        val state = SessionReducer.reduce(SessionState(), hello)
        assertTrue(state.hello)
        assertEquals(listOf("hello", "hi"), state.items.map { (it as? UserItem)?.text ?: (it as AssistantTextItem).text })
        assertIs<ConnectionState.Connected>(b.state.value)
        assertEquals("/fake/continued.jsonl", b.sessionFile)
        assertNotNull(b.lastStartMs)
        assertEquals(listOf("--session", "/fake/continued.jsonl"), launches.single())
    }

    @Test
    fun continueNeverLetsPiPickTheNewestFile() = runBlocking {
        // Pi's --continue takes the newest file of the session folder, a subagent's own session included. The app's
        // newestSession lists only real sessions; with none, a cold "continue" starts a new session.
        val b = backend(newest = { null })
        val inbox = records(b)
        assertTrue(b.connect())
        assertEquals("/fake/new-1.jsonl", inbox.next("remote_hello").obj("state")?.str("sessionFile"))
        assertEquals(listOf(emptyList()), launches.toList())
    }

    @Test
    fun rpcClientRoundTripsThroughTheEngine() = runBlocking {
        val b = backend()
        val rpc = RpcClient(b, scope)
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        scope.launch { rpc.events.collect { events.send(it) } }
        assertTrue(b.connect())
        events.next("remote_hello")
        val response = rpc.prompt("ping")
        assertTrue(response.success)
        assertEquals("started", response.dataObject?.str("disposition"))
        val update = events.next("message_update")
        assertEquals("echo: ping", update.obj("assistantMessageEvent")?.str("delta"))
        events.next("agent_settled")
        val state = rpc.getState()
        assertEquals(4, state.dataObject?.int("messageCount"))
        val bash = rpc.bash("ls")
        assertEquals("ran: ls", bash.dataObject?.str("output"))
        // Streamed output carries the caller's own id back, not the backend's prefixed one.
        val streamed = events.next("bash_execution_update")
        assertTrue(streamed.str("id")!!.startsWith("a"))
        rpc.stop()
    }

    @Test
    fun aSessionSwitchIsFollowedByANewSnapshot() = runBlocking {
        val b = backend()
        val rpc = RpcClient(b, scope)
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        scope.launch { rpc.events.collect { events.send(it) } }
        assertTrue(b.connect())
        assertEquals(2, events.next("remote_hello").arr("messages")?.size)
        val response = rpc.newSession()
        assertTrue(response.success)
        val hello = events.next("remote_hello")
        assertEquals(0, hello.arr("messages")?.size)
        assertEquals("/fake/new-1.jsonl", hello.obj("state")?.str("sessionFile"))
        assertTrue(rpc.switchSession("/fake/other.jsonl").success)
        assertEquals("/fake/other.jsonl", events.next("remote_hello").obj("state")?.str("sessionFile"))
        assertEquals("/fake/other.jsonl", b.sessionFile)
        rpc.stop()
    }

    @Test
    fun recordsWithLineSeparatorsAndMegabytesArriveWhole() = runBlocking {
        val b = backend()
        val inbox = records(b)
        assertTrue(b.connect())
        inbox.next("remote_hello")
        assertTrue(b.send(buildJsonObject { put("id", "x1"); put("type", "prompt"); put("message", "big") }))
        val delta = inbox.next("message_update").obj("assistantMessageEvent")?.str("delta")!!
        assertEquals(3_000_000 + 4, delta.length)
        assertTrue(delta.endsWith(" end"))
    }

    @Test
    fun aCrashIsReportedAndReconnectResumesTheSameSession() = runBlocking {
        val file = continuedFile()
        val b = backend(env = mapOf("FAKE_CONTINUE_FILE" to file.path))
        val inbox = records(b)
        assertTrue(b.connect())
        inbox.next("remote_hello")
        b.send(buildJsonObject { put("id", "c1"); put("type", "prompt"); put("message", "crash") })
        val end = withTimeout(20_000) { b.state.first { it is ConnectionState.Disconnected } } as ConnectionState.Disconnected
        assertEquals(DisconnectReason.Lost, end.reason)
        assertTrue(end.detail!!.contains("exit code 7"), end.detail)
        assertTrue(b.stderr.text().contains("crashing"))
        assertTrue(b.connect())
        val hello = inbox.next("remote_hello")
        assertEquals(file.path, hello.obj("state")?.str("sessionFile"))
        assertEquals(listOf("--session", file.path), launches.last())
    }

    @Test
    fun repeatedCrashesStopTheAutomaticRespawn() = runBlocking {
        val file = continuedFile()
        val b = backend(env = mapOf("FAKE_CONTINUE_FILE" to file.path))
        val inbox = records(b)
        var last: ConnectionState.Disconnected? = null
        repeat(3) {
            assertTrue(b.connect())
            inbox.next("remote_hello")
            b.send(buildJsonObject { put("id", "c$it"); put("type", "prompt"); put("message", "crash") })
            last = withTimeout(20_000) { b.state.first { s -> s is ConnectionState.Disconnected } } as ConnectionState.Disconnected
        }
        assertEquals(DisconnectReason.EngineFailed, last!!.reason)
        assertFalse(last!!.reason.retryable)
    }

    @Test
    fun anEngineThatExitsAtStartFailsWithItsStderr() = runBlocking {
        val b = backend(env = mapOf("FAKE_EXIT_AT_START" to "3"))
        assertFalse(b.connect())
        val end = b.state.value as ConnectionState.Disconnected
        assertEquals(DisconnectReason.EngineFailed, end.reason)
        assertTrue(end.detail!!.contains("exit code 3"), end.detail)
        assertTrue(end.detail!!.contains("failing on purpose"), end.detail)
    }

    @Test
    fun aLauncherErrorFailsTheConnect() = runBlocking {
        val b = backend(launcher = { _, _ -> throw IllegalStateException("no engine in this build") })
        assertFalse(b.connect())
        val end = b.state.value as ConnectionState.Disconnected
        assertEquals(DisconnectReason.EngineFailed, end.reason)
        assertTrue(end.detail!!.contains("no engine in this build"))
    }

    @Test
    fun aWarmEngineOpensANewSessionWhenAskedForAFreshOne() = runBlocking {
        val b = backend()
        assertTrue(b.warmUp())
        assertIs<ConnectionState.Idle>(b.state.value)
        b.target = SessionTarget.Fresh
        val inbox = records(b)
        assertTrue(b.connect())
        val hello = inbox.next("remote_hello")
        assertEquals(0, hello.arr("messages")?.size)
        assertEquals("/fake/new-1.jsonl", hello.obj("state")?.str("sessionFile"))
        assertEquals(1, launches.size)
    }

    @Test
    fun aWarmEngineCatchesUpWithTheNewestSessionOnContinue() = runBlocking {
        val b = backend(newest = { "/fake/newer.jsonl" })
        assertTrue(b.warmUp())
        val inbox = records(b)
        assertTrue(b.connect())
        assertEquals("/fake/newer.jsonl", inbox.next("remote_hello").obj("state")?.str("sessionFile"))
    }

    @Test
    fun aSpareEngineMovesToAnotherProject() = runBlocking {
        // A saved session of the other project: switch to it.
        val saved = continuedFile()
        saved.writeText("{\"type\":\"session\",\"version\":3,\"id\":\"s1\",\"timestamp\":\"t\",\"cwd\":\"/projects/other\"}\n")
        val b = backend(target = SessionTarget.Fresh, newest = { cwd -> saved.path.takeIf { cwd == "/projects/other" } })
        assertTrue(b.warmUp())
        b.assign(b.config.copy(name = "other", cwd = "/projects/other"), SessionTarget.Continue)
        val inbox = records(b)
        assertTrue(b.connect())
        val hello = inbox.next("remote_hello")
        assertEquals("other", hello.obj("host")?.str("name"))
        assertEquals("/projects/other", hello.obj("host")?.str("cwd"))
        assertEquals(saved.path, hello.obj("state")?.str("sessionFile"))
        assertEquals(listOf("/projects/demo"), launchDirs.toList())
    }

    @Test
    fun aSpareEngineStartsANewSessionInAnotherProjectThroughAnEmptySessionFile() = runBlocking {
        val created = continuedFile()
        created.writeText("{\"type\":\"session\",\"version\":3,\"id\":\"n1\",\"timestamp\":\"t\",\"cwd\":\"/projects/third\"}\n")
        val b = backend(target = SessionTarget.Fresh, fresh = { cwd -> created.path.takeIf { cwd == "/projects/third" } })
        assertTrue(b.warmUp())
        b.assign(b.config.copy(name = "third", cwd = "/projects/third"), SessionTarget.Fresh)
        val inbox = records(b)
        assertTrue(b.connect())
        assertEquals(created.path, inbox.next("remote_hello").obj("state")?.str("sessionFile"))
        // In its own project a new session is a plain new_session.
        b.park()
        b.assign(b.config, SessionTarget.Fresh)
        assertTrue(b.connect())
        assertEquals("/fake/new-2.jsonl", inbox.next("remote_hello").obj("state")?.str("sessionFile"))
        assertEquals(1, launches.size)
    }

    @Test
    fun aParkedEngineServesTheNextConsumerWithoutRespawning() = runBlocking {
        val b = backend()
        val first = RpcClient(b, scope)
        val firstEvents = Channel<JsonObject>(Channel.UNLIMITED)
        val job = scope.launch { first.events.collect { firstEvents.send(it) } }
        assertTrue(b.connect())
        firstEvents.next("remote_hello")
        // An answer that arrives after the hand-over must not reach the next consumer.
        val late = scope.launch { first.prompt("slow") }
        delay(100)
        first.stop()
        job.cancel()
        late.cancel()
        b.park()
        assertIs<ConnectionState.Idle>(b.state.value)
        assertFalse(b.send(buildJsonObject { put("type", "get_state") }))

        b.target = SessionTarget.Resume("/fake/picked.jsonl")
        val second = RpcClient(b, scope)
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        scope.launch { second.events.collect { events.send(it) } }
        assertTrue(b.connect())
        assertEquals("/fake/picked.jsonl", events.next("remote_hello").obj("state")?.str("sessionFile"))
        delay(1_200)
        // The second client's first request has the same id ("a1") as the first one's slow prompt.
        val state = second.getState()
        assertTrue(state.success)
        assertEquals("get_state", state.command)
        assertEquals(1, launches.size)
        second.stop()
    }

    @Test
    fun openDialogsAreInTheSnapshotAndAnswersReachTheEngine() = runBlocking {
        val b = backend()
        val inbox = records(b)
        assertTrue(b.connect())
        inbox.next("remote_hello")
        b.send(buildJsonObject { put("id", "p1"); put("type", "prompt"); put("message", "dialog") })
        assertEquals("d1", inbox.next("extension_ui_request").str("id"))
        b.park()
        assertTrue(b.connect())
        val hello = inbox.next("remote_hello")
        assertEquals(listOf("d1"), hello.arr("dialogs")?.map { (it as JsonObject).str("id") })
        b.send(buildJsonObject { put("type", "extension_ui_response"); put("id", "d1"); put("confirmed", true) })
        assertEquals("answered d1 true", inbox.next("extension_ui_request").str("message"))
    }

    @Test
    fun anOpenSheetIsInTheSnapshotAsItIsNow() = runBlocking {
        val b = backend()
        val inbox = records(b)
        assertTrue(b.connect())
        inbox.next("remote_hello")
        b.send(buildJsonObject { put("id", "p1"); put("type", "prompt"); put("message", "sheet") })
        assertEquals("s1", inbox.next("extension_ui_request").str("id"))
        assertEquals("updateView", inbox.next("extension_ui_request").str("method"))
        b.park()
        assertTrue(b.connect())
        val sheet = inbox.next("remote_hello").arr("dialogs")?.single() as JsonObject
        assertEquals("showView", sheet.str("method"))
        assertEquals(2, sheet.long("rev"))
        val field = sheet.obj("view")?.arr("body")?.single() as JsonObject
        assertEquals("next", field.str("value"))
    }

    @Test
    fun closeStopsTheProcess() = runBlocking {
        val b = backend()
        val inbox = records(b)
        assertTrue(b.connect())
        inbox.next("remote_hello")
        assertTrue(b.isAlive)
        b.closeAndAwait()
        assertFalse(b.isAlive)
        assertEquals(DisconnectReason.Closed, (b.state.value as ConnectionState.Disconnected).reason)
        assertFalse(b.send(buildJsonObject { put("id", JsonPrimitive("z")); put("type", "get_state") }))
    }
}
