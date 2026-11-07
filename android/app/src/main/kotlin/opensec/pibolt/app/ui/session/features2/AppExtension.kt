package opensec.pibolt.app.ui.session.features2

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.str
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * A small Pi extension the app writes to `<agentDir>/extensions/pibolt-app.js`. RPC has no command to move the session
 * tree's leaf, set a label or list tools; an extension command can (`ctx.navigateTree`, `pi.setLabel`,
 * `pi.getAllTools`). The app runs it as the prompt `/pibolt-app <result file> <action> [args]` (an extension command
 * runs at once and its `prompt` response comes when it finishes) and reads the result file it wrote.
 *
 * The command is hidden from the palette ([HIDDEN_COMMAND]).
 */
object AppExtension {
    const val HIDDEN_COMMAND = "pibolt-app"
    const val FILE = "pibolt-app.js"

    /** Bumped with every change to [SOURCE]; the file is rewritten when its first line differs. */
    private const val VERSION = 1

    val SOURCE = """
        |// pibolt-app v$VERSION: written by the Pi-Bolt Android app; do not edit (it is rewritten).
        |import { writeFileSync } from "node:fs";
        |
        |export default function (pi) {
        |  pi.registerCommand("$HIDDEN_COMMAND", {
        |    description: "Pi-Bolt Android app helper",
        |    handler: async (args, ctx) => {
        |      const text = (args ?? "").trim();
        |      const [out, action, ...rest] = text.split(/\s+/);
        |      const write = (value) => { try { writeFileSync(out, JSON.stringify(value)); } catch {} };
        |      try {
        |        if (action === "goto") {
        |          const result = await ctx.navigateTree(rest[0], { summarize: false });
        |          write({ ok: true, cancelled: !!result.cancelled, editorText: result.editorText ?? null });
        |        } else if (action === "label") {
        |          const label = rest.slice(1).join(" ");
        |          pi.setLabel(rest[0], label.length > 0 ? label : undefined);
        |          write({ ok: true });
        |        } else if (action === "tools") {
        |          const tools = pi.getAllTools().map((t) => ({ name: t.name, description: t.description ?? "", sourceInfo: t.sourceInfo ?? null }));
        |          write({ ok: true, tools, active: pi.getActiveTools() });
        |        } else {
        |          write({ ok: false, error: "unknown action " + action });
        |        }
        |      } catch (error) {
        |        write({ ok: false, error: error instanceof Error ? error.message : String(error) });
        |      }
        |    },
        |  });
        |}
        |""".trimMargin()

    /** Writes the extension if missing or older. Blocking; false when the agent dir cannot be written. */
    fun install(agentDir: File): Boolean = runCatching {
        val dir = File(agentDir, "extensions").apply { mkdirs() }
        val file = File(dir, FILE)
        val firstLine = SOURCE.lineSequence().first()
        if (file.isFile && file.bufferedReader().use { it.readLine() } == firstLine) return true
        val temp = File(dir, ".$FILE.${System.nanoTime()}.tmp")
        temp.writeText(SOURCE)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrDefault(false)

    /** True when [name] is the helper's command (kept out of the palette and lists). */
    fun isHelper(name: String): Boolean = name == HIDDEN_COMMAND || name.startsWith("$HIDDEN_COMMAND:")

    /**
     * Runs `/pibolt-app <out> <action> <args>` in [controller] and returns what the command wrote. Fails when the
     * engine refused the prompt, or the helper is not loaded yet (written after this engine started: /reload).
     */
    suspend fun run(controller: SessionController, tmpDir: File, action: String, args: String = ""): Result<JsonObject> {
        tmpDir.mkdirs()
        val out = File(tmpDir, "pibolt-app-${UUID.randomUUID()}.json")
        try {
            val message = "/$HIDDEN_COMMAND ${out.absolutePath} $action $args".trimEnd()
            val response = controller.request("prompt", timeoutMs = 60_000) { put("message", message) }
            if (!response.success) return Result.failure(IllegalStateException(response.error ?: "The engine refused the command"))
            if (!out.isFile) return Result.failure(IllegalStateException("Reload the session and try again."))
            val json = ProtocolJson.parseToJsonElement(out.readText()) as? JsonObject
                ?: return Result.failure(IllegalStateException("No answer from the session"))
            if (json.bool("ok") != true) return Result.failure(IllegalStateException(json.str("error") ?: "The command failed"))
            return Result.success(json)
        } finally {
            out.delete()
        }
    }
}
