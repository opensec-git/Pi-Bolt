package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.session.toolLabel
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.app.preview.ConsoleBuffer
import opensec.pibolt.app.preview.PreviewBroker
import opensec.pibolt.app.preview.PreviewCommand
import opensec.pibolt.app.preview.PreviewExtension
import opensec.pibolt.app.preview.PreviewOrigins
import opensec.pibolt.app.preview.PreviewOrigins.Target
import opensec.pibolt.app.preview.PreviewReply
import opensec.pibolt.app.preview.PreviewRequest
import opensec.pibolt.app.preview.PreviewRequests
import opensec.pibolt.app.preview.PreviewScripts
import opensec.pibolt.app.preview.ScreenshotScale
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewToolsTest {
    private fun parse(line: String) = PreviewRequests.parse(line)

    private fun command(op: String, args: String = "{}") = parse("""{"op":"$op","cwd":"/p","args":$args}""").getOrThrow().command

    private fun refused(op: String, args: String) = parse("""{"op":"$op","cwd":"/p","args":$args}""").exceptionOrNull()?.message

    @Test
    fun requestsAreCheckedAgainInTheApp() {
        assertEquals(PreviewRequest("/p", PreviewCommand.Screenshot), parse("""{"op":"screenshot","cwd":"/p"}""").getOrThrow())
        assertEquals(PreviewCommand.Click("#save", null, null), command("click", """{"selector":" #save "}"""))
        assertEquals(PreviewCommand.Click(null, 10.0, 20.5), command("click", """{"x":10,"y":20.5}"""))
        assertTrue(refused("click", """{"selector":"a","x":1,"y":2}""")!!.contains("not both"))
        assertTrue(refused("click", "{}")!!.contains("selector"))
        assertTrue(refused("click", """{"x":1}""")!!.contains("x and y"))
        assertTrue(refused("click", """{"x":-1,"y":2}""")!!.contains("0 or more"))
        assertTrue(refused("click", """{"selector":"${"a".repeat(1001)}"}""")!!.contains("too long"))
        assertEquals(PreviewCommand.Console(12, true), command("console", """{"since":12.9,"errorsOnly":true}"""))
        assertTrue(refused("console", """{"since":-3}""") != null)
        assertEquals(PreviewCommand.Type("#q", "", false), command("type", """{"selector":"#q","text":""}"""))
        assertTrue(refused("type", """{"selector":"#q"}""") != null)
        assertTrue(refused("scroll", "{}") != null)
        assertTrue(refused("scroll", """{"to":"left"}""") != null)
        assertEquals(PreviewCommand.Scroll(null, -200.0, null), command("scroll", """{"dy":-200}"""))
        assertTrue(refused("eval", """{"js":"  "}""") != null)
        assertEquals(PreviewCommand.Open(null, false), command("open"))
        assertTrue(refused("nope", "{}")!!.contains("unknown"))
        // A request without an absolute cwd is no session's.
        assertTrue(parse("""{"op":"screenshot","cwd":"rel"}""").isFailure)
        assertTrue(parse("""[1]""").isFailure)
        assertTrue(parse("not json").isFailure)
    }

    @Test
    fun onlyLoopbackHttpOnDetectedPorts() {
        assertTrue(PreviewOrigins.isLoopbackHttp("http://localhost:5173/"))
        assertTrue(PreviewOrigins.isLoopbackHttp("http://127.0.0.1:3000/a?b#c"))
        assertTrue(PreviewOrigins.isLoopbackHttp("HTTP://LOCALHOST:5173/"))
        for (url in listOf(
            "https://localhost:5173/", "http://localhost.evil.com:5173/", "http://localhost:5173@evil.com/", "http://user@localhost:5173/",
            "http://10.0.0.2:80/", "file:///etc/passwd", "javascript:alert(1)", "about:blank", "chrome-error://chromewebdata/", "", "http://[::1]:5173/",
        )) assertFalse(PreviewOrigins.isLoopbackHttp(url), url)
        assertEquals(80, PreviewOrigins.portOf("http://localhost/"))
        assertEquals(5173, PreviewOrigins.portOf("http://localhost:5173/x"))
        assertNull(PreviewOrigins.portOf("https://example.com/"))
        assertTrue(PreviewOrigins.allowed("http://localhost:5173/", listOf(5173)))
        assertFalse(PreviewOrigins.allowed("http://localhost:8080/", listOf(5173)))
    }

    @Test
    fun openResolvesToTheDevServer() {
        assertEquals(Target.Url("http://localhost:5173/"), PreviewOrigins.resolve(null, null, listOf(5173, 8080)))
        // The page already open wins over the first detected port.
        assertEquals(Target.Url("http://localhost:8080/cart"), PreviewOrigins.resolve(null, "http://localhost:8080/cart", listOf(5173, 8080)))
        assertEquals(Target.Url("http://localhost:8080/about"), PreviewOrigins.resolve("/about", "http://localhost:8080/cart", listOf(5173, 8080)))
        assertEquals(Target.Url("http://localhost:5173/settings?tab=2"), PreviewOrigins.resolve("/settings?tab=2", null, listOf(5173)))
        assertEquals(Target.Url("http://127.0.0.1:3000/"), PreviewOrigins.resolve("http://127.0.0.1:3000/", null, listOf(3000)))
        assertEquals(Target.Refused(PreviewOrigins.NO_SERVER), PreviewOrigins.resolve(null, null, emptyList()))
        assertEquals(Target.Refused(PreviewOrigins.NO_SERVER), PreviewOrigins.resolve("/about", null, emptyList()))
        val other = PreviewOrigins.resolve("http://localhost:9999/", null, listOf(5173))
        assertTrue(other is Target.Refused && other.message.contains("9999") && other.message.contains("localhost:5173"), other.toString())
        for (url in listOf("https://example.com/", "//example.com/", "http://localhost.evil.com:5173/", "file:///sdcard/x.html")) {
            assertTrue(PreviewOrigins.resolve(url, null, listOf(5173)) is Target.Refused, url)
        }
        // A page that is open but no longer allowed (another app's port) is not reused.
        assertEquals(Target.Url("http://localhost:5173/"), PreviewOrigins.resolve(null, "http://localhost:4444/", listOf(5173)))
    }

    @Test
    fun screenshotsFitIn1280AndTapsMapBack() {
        assertEquals(ScreenshotScale.Size(588, 1280), ScreenshotScale.fit(1080, 2350))
        assertEquals(ScreenshotScale.Size(1280, 588), ScreenshotScale.fit(2350, 1080))
        assertEquals(ScreenshotScale.Size(720, 1280), ScreenshotScale.fit(720, 1280))
        assertEquals(ScreenshotScale.Size(400, 300), ScreenshotScale.fit(400, 300))
        assertEquals(ScreenshotScale.Size(1, 1280), ScreenshotScale.fit(1, 10_000))
        assertEquals(ScreenshotScale.Size(0, 0), ScreenshotScale.fit(0, 100))
        val (fx, fy) = ScreenshotScale.fraction(294.0, 640.0, 1080, 2350)!!
        assertEquals(0.5, fx, 1e-9)
        assertEquals(0.5, fy, 1e-9)
        assertNull(ScreenshotScale.fraction(589.0, 10.0, 1080, 2350))
        assertNull(ScreenshotScale.fraction(1.0, 1.0, 0, 0))
        assertTrue(ScreenshotScale.looksBlank(IntArray(10_000) { -1 }))
        assertFalse(ScreenshotScale.looksBlank(IntArray(10_000) { if (it == 97 * 40) 0 else -1 }))
    }

    @Test
    fun consoleKeepsTheNewestWithCursors() {
        val buffer = ConsoleBuffer(capacity = 3, maxChars = 10)
        buffer.add(ConsoleBuffer.Level.Log, "one")
        buffer.add(ConsoleBuffer.Level.Warn, "two")
        val first = buffer.since(null)
        assertEquals(listOf("one", "two"), first.entries.map { it.text })
        assertEquals(2, first.next)
        buffer.add(ConsoleBuffer.Level.Error, "three is a long message")
        buffer.add(ConsoleBuffer.Level.Log, "four")
        val next = buffer.since(first.next)
        assertEquals(listOf("three is a…", "four"), next.entries.map { it.text })
        assertEquals(0, next.dropped)
        // "one" fell out of the ring: a reader from the start learns it missed one.
        val all = buffer.since(0)
        assertEquals(3, all.entries.size)
        assertEquals(1, all.dropped)
        assertEquals(listOf("two", "three is a…"), buffer.since(0, errorsOnly = true).entries.map { it.text })
        assertEquals(1, buffer.errorsSince(first.next))
        assertEquals(0, buffer.since(4).entries.size)
    }

    @Test
    fun hookedErrorsCarryTheirStackOnce() {
        val buffer = ConsoleBuffer()
        val hook = """{"message":"Uncaught TypeError: x is not a function","source":"http://localhost:5173/src/main.js","line":12,"stack":"TypeError: x is not a function\n    at go (http://localhost:5173/src/main.js:12:3)\n    at http://localhost:5173/src/main.js:20:1"}"""
        buffer.add(ConsoleBuffer.Level.Error, ConsoleBuffer.HOOK_MARK + hook)
        // Chromium reports the same error once more: skipped.
        buffer.add(ConsoleBuffer.Level.Error, "Uncaught TypeError: x is not a function", "http://localhost:5173/src/main.js", 12)
        buffer.add(ConsoleBuffer.Level.Error, "Uncaught TypeError: x is not a function", "http://localhost:5173/src/main.js", 12)
        val entries = buffer.since(null).entries
        assertEquals(2, entries.size)
        assertEquals("Uncaught TypeError: x is not a function\n    at go (http://localhost:5173/src/main.js:12:3)\n    at http://localhost:5173/src/main.js:20:1", entries[0].text)
        assertEquals(12, entries[0].line)
        // A malformed hook line is kept as it came.
        buffer.add(ConsoleBuffer.Level.Error, ConsoleBuffer.HOOK_MARK + "{oops")
        assertTrue(buffer.since(2).entries.single().text.startsWith(ConsoleBuffer.HOOK_MARK))
    }

    @Test
    fun consoleTextIsACompactLog() {
        val buffer = ConsoleBuffer()
        assertTrue(ConsoleBuffer.format(buffer.since(null)).startsWith("No new console messages."))
        buffer.add(ConsoleBuffer.Level.Page, "loading http://localhost:5173/")
        buffer.add(ConsoleBuffer.Level.Error, "Uncaught ReferenceError: foo is not defined", "http://localhost:5173/src/App.jsx", 7)
        buffer.add(ConsoleBuffer.Level.Network, "404 GET http://localhost:5173/logo.svg")
        val text = ConsoleBuffer.format(buffer.since(null))
        assertEquals(
            """
            3 messages, 1 error:
            — loading http://localhost:5173/ —
            [error] Uncaught ReferenceError: foo is not defined (App.jsx:7)
            [network] 404 GET http://localhost:5173/logo.svg

            Cursor: 3 (pass since=3 for newer messages)
            """.trimIndent(),
            text,
        )
        // Too much text: the newest entries are kept and the rest counted.
        repeat(50) { buffer.add(ConsoleBuffer.Level.Log, "line $it ".repeat(10)) }
        val cut = ConsoleBuffer.format(buffer.since(null), maxChars = 500)
        assertTrue(cut.contains("older messages not shown"), cut)
        assertTrue(cut.contains("line 49"), cut)
        assertFalse(cut.contains("line 0 "), cut)
    }

    @Test
    fun scriptsEmbedArgumentsAsLiterals() {
        val nasty = "\"); alert(1); (\"\n</script>"
        val script = PreviewScripts.clickSelector(nasty)
        assertTrue(script.contains(PreviewScripts.lit(nasty)))
        assertFalse(script.contains(nasty))
        assertEquals("\"a\\\"b\\nc\"", PreviewScripts.lit("a\"b\nc"))
        assertTrue(PreviewScripts.type("#q", "x", submit = true).contains("if (true)"))
        assertTrue(PreviewScripts.clickAt(0.25, 0.5).contains("0.25 * window.innerWidth"))
        assertTrue(PreviewScripts.scroll(null, null, "top").contains("const sel = null;"))
        assertTrue(PreviewScripts.ERROR_HOOK.contains(PreviewScripts.lit(ConsoleBuffer.HOOK_MARK)))
    }

    @Test
    fun brokerAnswersOneLinePerRequest() {
        fun serve(input: String, handle: suspend (PreviewRequest) -> PreviewReply = { PreviewReply.text("ok ${it.command}") }): JsonObject {
            val out = ByteArrayOutputStream()
            PreviewBroker.serve(ByteArrayInputStream(input.toByteArray()), out, handle)
            val text = out.toString(Charsets.UTF_8.name())
            assertTrue(text.endsWith("\n") && text.count { it == '\n' } == 1, text)
            return ProtocolJson.parseToJsonElement(text) as JsonObject
        }
        assertEquals("ok Screenshot", serve("""{"op":"screenshot","cwd":"/p"}""" + "\n").str("text"))
        val shot = serve("""{"op":"screenshot","cwd":"/p"}""" + "\n") { PreviewReply(ok = true, text = "s", image = "iVBOR") }
        assertEquals("image/png", shot.obj("image")?.str("mimeType"))
        assertEquals(false, serve("""{"op":"click","cwd":"/p","args":{}}""" + "\n").bool("ok"))
        // No newline before the end, or a line past the limit: an error answer, never a hang.
        assertEquals(false, serve("""{"op":"screenshot","cwd":"/p"}""").bool("ok"))
        assertEquals(false, serve("x".repeat(PreviewBroker.MAX_REQUEST + 10) + "\n").bool("ok"))
        assertTrue(serve("""{"op":"screenshot","cwd":"/p"}""" + "\n") { error("boom") }.str("error")!!.contains("boom"))
    }

    @Test
    fun brokerSocketIsPrivateAndNamedForProjectEngines() {
        val dir = Files.createTempDirectory("pibolt-preview").toFile()
        try {
            val socket = File(dir, "preview/socket")
            var bound: File? = null
            val broker = PreviewBroker(socket, { path, _ -> bound = path; java.io.Closeable {} }) { PreviewReply.text("x") }
            assertEquals(mapOf(PreviewBroker.SOCKET_VAR to socket.path), broker.environment())
            assertEquals(socket, bound)
            assertEquals(setOf("OWNER_READ", "OWNER_WRITE", "OWNER_EXECUTE"), Files.getPosixFilePermissions(socket.parentFile.toPath()).map { it.name }.toSet())
            val failing = PreviewBroker(socket, { _, _ -> throw java.io.IOException("no") }) { PreviewReply.text("x") }
            assertEquals(emptyMap(), failing.environment())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun previewToolRowsReadAsSteps() {
        fun label(name: String, args: Map<String, kotlinx.serialization.json.JsonElement>) =
            toolLabel(ToolItem("tool:1", "1", name, JsonObject(args), "", ToolStatus.Done, "", "ok"))
        assertEquals("Took a screenshot", label("preview_screenshot", emptyMap()).past)
        assertEquals("120, 341", label("preview_click", mapOf("x" to JsonPrimitive(120), "y" to JsonPrimitive(340.6))).detail)
        assertEquals("#save", label("preview_click", mapOf("selector" to JsonPrimitive("#save"))).detail)
        assertEquals("http://localhost:5173/", label("preview_open", mapOf("url" to JsonPrimitive("http://localhost:5173/"))).detail)
        assertEquals("document.title", label("preview_eval", mapOf("js" to JsonPrimitive("document.title\n// more"))).detail)
    }

    private val asset = File("src/main/assets/${PreviewExtension.ASSET}")

    @Test
    fun extensionIsInstalledAndKeptCurrent() {
        assertTrue(asset.isFile, "run from the app module (${asset.absolutePath})")
        val agentDir = Files.createTempDirectory("pibolt-agent").toFile()
        try {
            assertTrue(PreviewExtension.install(agentDir) { asset.inputStream() })
            val installed = File(agentDir, "extensions/${PreviewExtension.FILE}")
            assertEquals(asset.readText(), installed.readText())
            installed.writeText("// an older version")
            assertTrue(PreviewExtension.install(agentDir) { asset.inputStream() })
            assertEquals(asset.readText(), installed.readText())
            assertEquals(listOf(PreviewExtension.FILE), File(agentDir, "extensions").list()!!.toList())
            assertFalse(PreviewExtension.install(agentDir) { throw java.io.IOException("no asset") })
        } finally {
            agentDir.deleteRecursively()
        }
        // The extension and the app agree on the variable and the ops.
        val source = asset.readText()
        assertTrue(source.contains("\"${PreviewBroker.SOCKET_VAR}\""))
        for (op in listOf("open", "screenshot", "console", "click", "type", "scroll", "eval")) assertTrue(source.contains("name: \"preview_$op\""), op)
    }

    @Test
    fun extensionNodeTestsPass() {
        val node = listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { File(it).canExecute() }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "node") }.firstOrNull { it.canExecute() }?.path
        assumeTrue("node is not installed", node != null)
        val process = ProcessBuilder(node, "--test", "src/test/js/pibolt-preview.test.mjs").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "node --test timed out")
        assertEquals(0, process.exitValue(), output)
    }
}
