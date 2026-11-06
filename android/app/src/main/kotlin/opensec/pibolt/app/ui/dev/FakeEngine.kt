package opensec.pibolt.app.ui.dev

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.BackgroundFile
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.engine.EngineStatus
import opensec.pibolt.app.engine.ProviderAuth
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.Base64Url
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.Usage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * DEBUG ONLY: a stand-in for the on-device engine so the native screens can be built and screenshotted without it.
 * Reached only through [DevEngine] under `BuildConfig.DEBUG` (R8 drops it from release). Delete with ui/dev/.
 *
 * - Projects are real folders under `filesDir/dev-projects`, seeded with three samples, so create, rename and delete
 *   really run.
 * - Sessions are in memory: seeded per sample project, plus one per "New session".
 * - [open] returns a real [SessionController] that never connects: its state is set once (by reflection) from a
 *   scripted `remote_hello`, so the session screen renders a finished, a running or an empty session.
 * - [scenario] and the provider set are changed from the hidden developer sheet (Settings > About, tap Version 5x).
 */
class FakeEngine(private val app: PiBoltApp) : EngineApi {
    enum class Scenario(val label: String) { Ready("Ready"), Preparing("Preparing"), Failed("Failed"), Unavailable("Unavailable") }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _scenario = MutableStateFlow(Scenario.Ready)
    val scenario: StateFlow<Scenario> = _scenario.asStateFlow()

    private val root by lazy { File(app.filesDir, "dev-projects") }
    private val seeded by lazy { seed() }

    override val projectsDir: File
        get() {
            seeded
            return root
        }

    private val sessions = ConcurrentHashMap<String, MutableList<SessionSummary>>()
    private var live: SessionController? = null
    private var prepareJob: Job? = null
    private var streamJob: Job? = null

    override fun prepare() {
        if (_status.value !is EngineStatus.Idle && _status.value !is EngineStatus.Failed) return
        start(_scenario.value)
    }

    fun setScenario(s: Scenario) {
        _scenario.value = s
        start(s)
    }

    private fun start(s: Scenario) {
        prepareJob?.cancel()
        prepareJob = scope.launch {
            _status.value = EngineStatus.Preparing("Linking tools")
            delay(700)
            _status.value = EngineStatus.Preparing("Starting Pi")
            delay(500)
            _status.value = when (s) {
                Scenario.Ready -> EngineStatus.Ready("1.1.1")
                Scenario.Preparing -> EngineStatus.Preparing("Copying certificates")
                Scenario.Failed -> EngineStatus.Failed(
                    message = "Pi-Bolt stopped while starting",
                    detail = "libpibolt.so exited with status 127\nld-linux-aarch64.so.1: libz.so.1: cannot open shared object file\n(prefix /data/data/in.opensec.pibolt/files/usr)",
                    canRetry = true,
                )
                Scenario.Unavailable -> EngineStatus.Unavailable("Pi-Bolt can't run in a work profile or for a secondary user on this phone.")
            }
        }
    }

    override fun sessions(project: File): List<SessionSummary> {
        seeded
        return sessions[project.name].orEmpty().sortedByDescending { it.updatedAt }
    }

    override fun open(project: File, sessionFile: String?, fresh: Boolean): SessionController {
        seeded
        live?.stop()
        streamJob?.cancel()
        val list = sessions.getOrPut(project.name) { mutableListOf() }
        val now = System.currentTimeMillis()
        val file = when {
            fresh || (sessionFile == null && list.isEmpty()) -> {
                val id = "s${now.toString(36)}"
                val summary = SessionSummary("fake://${project.name}/$id", id, null, null, now, 0)
                list += summary
                summary.file
            }
            sessionFile != null -> sessionFile
            else -> list.maxBy { it.updatedAt }.file
        }
        val summary = list.firstOrNull { it.file == file }
        val controller = SessionController(app, FAKE_HOST, project.name, listOf("ws://127.0.0.1:9"), pairSecret = null)
        val state = when {
            summary == null || summary.messageCount == 0 -> FakeSessions.empty(project)
            summary.id == "running" -> FakeSessions.running(project, summary)
            summary.id == "long" -> FakeSessions.long(project, summary)
            summary.id == "stream" -> FakeSessions.empty(project)
            else -> FakeSessions.finished(project, summary)
        }
        set(controller, "_state", state)
        set(controller, "_link", LinkState.Connected("local"))
        live = controller
        if (summary?.id == "stream") streamJob = scope.launch { stream(controller, project) }
        return controller
    }

    override val auth = FakeAuth(app)

    /** A project with 10,000 files in one folder (plus a few around it), for the file browser's large-folder path. */
    fun createLargeProject() {
        scope.launch(Dispatchers.IO) {
            val gen = File(projectsDir, "large-repo/src/generated")
            gen.mkdirs()
            File(projectsDir, "large-repo/README.md").writeText("# large-repo\n")
            File(projectsDir, "large-repo/.git").mkdirs()
            File(projectsDir, "large-repo/.git/HEAD").writeText("ref: refs/heads/main\n")
            for (i in 0 until 10_000) File(gen, "file_%05d.rs".format(i)).writeText("pub const N: u32 = $i;\n")
            sessions.getOrPut("large-repo") { mutableListOf() }
        }
    }

    /**
     * Endless streaming for frame measurements: each turn a user message, then a long markdown reply (prose, lists, a
     * table, code) arriving in ~12-character deltas every 33 ms through the app's own reducer, then a 1 s pause.
     */
    private suspend fun stream(controller: SessionController, project: File) {
        var s = FakeSessions.empty(project)
        var turn = 0
        while (true) {
            turn++
            s = SessionReducer.reduce(s, buildJsonObject { put("type", "message_end"); put("message", buildJsonObject { put("role", "user"); put("content", "Explain the scanner, turn $turn") }) })
            s = SessionReducer.reduce(s, buildJsonObject { put("type", "agent_start") })
            s = SessionReducer.reduce(s, buildJsonObject { put("type", "message_start"); put("message", buildJsonObject { put("role", "assistant"); put("content", JsonArray(emptyList())) }) })
            s = SessionReducer.reduce(s, delta("text_start", null))
            set(controller, "_state", s)
            val text = FakeSessions.LONG_REPLY
            var i = 0
            while (i < text.length) {
                val next = (i + 12).coerceAtMost(text.length)
                s = SessionReducer.reduce(s, delta("text_delta", text.substring(i, next)))
                i = next
                set(controller, "_state", s)
                delay(33)
            }
            s = SessionReducer.reduce(s, buildJsonObject { put("type", "message_end"); put("message", buildJsonObject { put("role", "assistant"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }; put("stopReason", "stop") }) })
            s = SessionReducer.reduce(s, buildJsonObject { put("type", "agent_settled") })
            set(controller, "_state", s)
            delay(1000)
        }
    }

    private fun delta(type: String, text: String?) = buildJsonObject {
        put("type", "message_update")
        putJsonObject("assistantMessageEvent") {
            put("type", type)
            put("contentIndex", 0)
            text?.let { put("delta", it) }
        }
    }

    private fun set(controller: SessionController, field: String, value: Any) {
        val f = SessionController::class.java.getDeclaredField(field).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(controller) as MutableStateFlow<Any>).value = value
    }

    private fun seed() {
        root.mkdirs()
        val now = System.currentTimeMillis()
        val h = 3_600_000L
        val samples = mapOf(
            "pi-bolt-web" to listOf(
                SessionSummary("fake://pi-bolt-web/running", "running", null, "Add a dark mode toggle to the settings page", now - 4 * 60_000, 18),
                SessionSummary("fake://pi-bolt-web/login", "login", "Login form submits twice", "The login form submits twice when you press Enter", now - 2 * h, 24),
                SessionSummary("fake://pi-bolt-web/deps", "deps", null, "Update the dependencies and fix what breaks", now - 26 * h, 41),
                SessionSummary("fake://pi-bolt-web/ci", "ci", "CI cache", "Why is CI slower since last week?", now - 5 * 24 * h, 12),
            ),
            "rust-cli" to listOf(
                SessionSummary("fake://rust-cli/stream", "stream", "Streaming demo", "Explain the scanner", now - 20 * h, 2),
                SessionSummary("fake://rust-cli/long", "long", "Long session (200 messages)", "Walk me through the codebase", now - 22 * h, 200),
                SessionSummary("fake://rust-cli/args", "args", null, "Add a --json flag to the list command", now - 30 * h, 15),
                SessionSummary("fake://rust-cli/perf", "perf", "Speed up the scanner", "Profile the directory scanner, it is slow on big trees", now - 9 * 24 * h, 33),
            ),
            "notes" to emptyList(),
        )
        for ((name, list) in samples) {
            val dir = File(root, name)
            if (!dir.exists()) {
                dir.mkdirs()
                File(dir, "README.md").writeText("# $name\n")
            }
            sessions.getOrPut(name) { list.toMutableList() }
        }
    }

    /** Provider ids only (keys are dropped), kept in `dev-auth.txt` so screenshots survive restarts. */
    class FakeAuth(app: PiBoltApp) : ProviderAuth {
        private val set = MutableStateFlow<Set<String>>(emptySet())
        override val configured: StateFlow<Set<String>> = set.asStateFlow()
        private val file = BackgroundFile(app, "dev-auth.txt") { text -> set.value = text?.lines()?.filter { it.isNotBlank() }?.toSet().orEmpty() }

        override fun setApiKey(provider: String, key: String) = replace(set.value + provider)

        override fun remove(provider: String) = replace(set.value - provider)

        fun replace(ids: Set<String>) {
            set.value = ids
            file.write(ids.joinToString("\n"))
        }
    }

    private companion object {
        val FAKE_HOST: String = Base64Url.encode(ByteArray(32) { 7 })
    }
}

/** Scripted sessions for [FakeEngine], built with the app's own reducer from Pi's JSON shapes. */
private object FakeSessions {
    private val model = buildJsonObject {
        put("id", "claude-sonnet-4-5")
        put("name", "Claude Sonnet 4.5")
        put("provider", "anthropic")
        put("reasoning", true)
        put("contextWindow", 200_000)
        putJsonArray("input") {
            add(JsonPrimitive("text"))
            add(JsonPrimitive("image"))
        }
    }

    private fun hello(project: File, summary: SessionSummary?, running: Boolean, messages: JsonArray): JsonObject = buildJsonObject {
        put("type", "remote_hello")
        putJsonObject("host") {
            put("name", project.name)
            put("version", "1.1.1")
            put("cwd", project.absolutePath)
        }
        putJsonObject("state") {
            put("model", model)
            put("thinkingLevel", "medium")
            put("isStreaming", running)
            put("isCompacting", false)
            summary?.name?.let { put("sessionName", it) }
            put("sessionId", summary?.id ?: "new")
        }
        put("messages", messages)
        putJsonArray("commands") {
            for ((name, description) in listOf("review" to "Review the working tree changes", "test" to "Run the project's tests", "commit" to "Write a commit message")) {
                add(
                    buildJsonObject {
                        put("name", name)
                        put("description", description)
                        put("source", "prompt")
                    },
                )
            }
        }
    }

    private fun user(text: String) = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    private fun text(t: String) = buildJsonObject {
        put("type", "text")
        put("text", t)
    }

    private fun call(id: String, name: String, args: JsonObject) = buildJsonObject {
        put("type", "toolCall")
        put("id", id)
        put("name", name)
        put("arguments", args)
    }

    private fun assistant(vararg blocks: JsonObject) = buildJsonObject {
        put("role", "assistant")
        put("content", JsonArray(blocks.toList()))
        put("stopReason", "stop")
    }

    private fun result(id: String, name: String, output: String) = buildJsonObject {
        put("role", "toolResult")
        put("toolCallId", id)
        put("toolName", name)
        putJsonArray("content") { add(text(output)) }
        put("isError", false)
    }

    private fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    fun empty(project: File): SessionState = SessionReducer.fromHello(hello(project, null, false, JsonArray(emptyList())))

    fun finished(project: File, summary: SessionSummary): SessionState {
        val messages = buildJsonArray {
            add(user(summary.preview ?: "Look around this project"))
            add(
                assistant(
                    text("I'll start with the form component."),
                    call("t1", "read", args("path" to "src/components/LoginForm.tsx")),
                ),
            )
            add(result("t1", "read", "export function LoginForm() {\n  const submit = useSubmit()\n  return <form onSubmit={submit} onKeyDown={(e) => e.key === 'Enter' && submit()}>\n"))
            add(
                assistant(
                    call("t2", "edit", JsonObject(mapOf("path" to JsonPrimitive("src/components/LoginForm.tsx"), "oldText" to JsonPrimitive("<form onSubmit={submit} onKeyDown={(e) => e.key === 'Enter' && submit()}>"), "newText" to JsonPrimitive("<form onSubmit={submit}>")))),
                ),
            )
            add(result("t2", "edit", "Successfully replaced text in src/components/LoginForm.tsx."))
            add(assistant(call("t3", "bash", args("command" to "npm test -- LoginForm"))))
            add(result("t3", "bash", "PASS src/components/LoginForm.test.tsx\n  ✓ renders (8 ms)\n  ✓ submits once on Enter (12 ms)\n\nTests: 2 passed, 2 total"))
            add(
                assistant(
                    text(
                        "Fixed. The form called `submit()` twice on Enter: once from `onSubmit` and once from its own `onKeyDown` handler.\n\n" +
                            "- Removed the `onKeyDown` handler; the browser already submits on Enter.\n- The existing tests pass, including **submits once on Enter**.",
                    ),
                ),
            )
        }
        return SessionReducer.fromHello(hello(project, summary, false, messages)).copy(lastUsage = Usage(41_200, 1_900, 30_000, 43_100, 0.12))
    }

    /** 100 turns: a question, then an answer with prose, a list and (every third turn) a code block or a tool call. */
    fun long(project: File, summary: SessionSummary): SessionState {
        val messages = buildJsonArray {
            for (t in 1..100) {
                add(user("Question $t: how does module $t handle errors?"))
                if (t % 3 == 0) {
                    add(assistant(call("l$t", "read", args("path" to "src/module_$t.rs"))))
                    add(result("l$t", "read", "pub fn run_$t() -> Result<()> {\n    scan()?;\n    Ok(())\n}\n"))
                }
                val code = if (t % 3 == 1) "\n\n```rust\npub fn run_$t() -> Result<(), Error> {\n    let files = scan(&root)?;\n    for f in files {\n        process(f).map_err(Error::from)?;\n    }\n    Ok(())\n}\n```" else ""
                add(assistant(text("Module $t returns `Result` and lets `?` carry errors up to `main`, which prints them with context.\n\n- Errors are wrapped with `anyhow::Context`\n- Retries happen only for I/O errors$code\n\nThat keeps the happy path short.")))
            }
        }
        return SessionReducer.fromHello(hello(project, summary, false, messages))
    }

    val LONG_REPLY: String = buildString {
        append("The scanner walks the tree breadth-first and **never follows symlinks**, so cycles cannot happen.\n\n")
        repeat(4) { n ->
            append("## Stage ${n + 1}\n\nEach stage reads a batch of directory entries, filters them against the ignore rules and pushes the survivors onto the queue. ")
            append("Batches keep memory flat even for trees with millions of files, and `rayon` spreads the filtering across cores.\n\n")
            append("- Reads entries with `read_dir` in chunks of 512\n- Applies `.gitignore` rules with a compiled glob set\n- Sends paths to the worker pool over a bounded channel\n\n")
            append("```rust\nfn stage_$n(queue: &mut VecDeque<PathBuf>, rules: &GlobSet) -> io::Result<Vec<PathBuf>> {\n    let mut out = Vec::with_capacity(512);\n    while let Some(dir) = queue.pop_front() {\n        for entry in fs::read_dir(&dir)? {\n            let path = entry?.path();\n            if !rules.is_match(&path) {\n                out.push(path);\n            }\n        }\n    }\n    Ok(out)\n}\n```\n\n")
            append("| Step | Cost | Notes |\n|---|---:|---|\n| read_dir | 1.2 ms | per 512 entries |\n| glob match | 0.3 ms | compiled once |\n| send | 0.05 ms | bounded channel |\n\n")
        }
        append("That is the whole pipeline: read, filter, send, repeat.")
    }

    fun running(project: File, summary: SessionSummary): SessionState {
        val messages = buildJsonArray {
            add(user(summary.preview ?: "Run the tests"))
            add(assistant(text("I'll add the toggle next to the theme picker."), call("r1", "read", args("path" to "src/pages/Settings.tsx"))))
            add(result("r1", "read", "export default function Settings() {\n  return <Page title=\"Settings\">…</Page>\n}\n"))
        }
        var s = SessionReducer.fromHello(hello(project, summary, true, messages))
        val callId = "r2"
        s = SessionReducer.reduce(
            s,
            buildJsonObject {
                put("type", "message_start")
                put("message", assistant(call(callId, "bash", args("command" to "npm run build"))))
            },
        )
        s = SessionReducer.reduce(
            s,
            buildJsonObject {
                put("type", "tool_execution_start")
                put("toolCallId", callId)
                put("toolName", "bash")
                put("args", args("command" to "npm run build"))
            },
        )
        s = SessionReducer.reduce(
            s,
            buildJsonObject {
                put("type", "tool_execution_update")
                put("toolCallId", callId)
                put("toolName", "bash")
                putJsonObject("partialResult") {
                    putJsonArray("content") { add(text("> pi-bolt-web@0.4.0 build\n> vite build\n\nvite v6.2.0 building for production...\ntransforming (412) src/pages/Settings.tsx")) }
                }
            },
        )
        return s.copy(isRunning = true, lastUsage = Usage(88_000, 2_100, 70_000, 90_100, 0.31))
    }
}
