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
    private const val VERSION = 2

    /** The status key the helper's `login` reports its steps under (auth URL, device code, progress) as JSON. */
    const val LOGIN_STATUS = "pibolt-login"

    // `providers`: the providers extensions register and those with a usable model, as Pi's `get_providers` describes
    // them (engines before 1.1.7 have no such command), after checking credentials again. `login <provider> <method>
    // <auth.json>`: the provider's own login flow (OAuth, or its API key setup): prompts as dialogs, its URL and device
    // code under [LOGIN_STATUS]; the credential is written to auth.json under Pi's lock, never to the result file.
    val SOURCE = """
        |// pibolt-app v$VERSION: written by the Pi-Bolt Android app; do not edit (it is rewritten).
        |import { mkdirSync, readFileSync, renameSync, rmdirSync, statSync, writeFileSync } from "node:fs";
        |
        |const envNames = (raw) => {
        |  if (typeof raw !== "string" || raw.startsWith("!")) return [];
        |  const names = [];
        |  for (const m of raw.replaceAll("$$", "").matchAll(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)/g)) names.push(m[1] ?? m[2]);
        |  return [...new Set(names)];
        |};
        |
        |const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
        |
        |// Pi's auth.json lock (proper-lockfile: a directory next to the file, stale after 10 s).
        |const saveCredential = async (file, id, credential) => {
        |  const lock = file + ".lock";
        |  for (let attempt = 0; ; attempt++) {
        |    try { mkdirSync(lock); break; } catch (error) {
        |      if (error?.code !== "EEXIST") throw error;
        |      try { if (Date.now() - statSync(lock).mtimeMs > 10000) { rmdirSync(lock); continue; } } catch {}
        |      if (attempt > 200) throw new Error("auth.json is locked");
        |      await sleep(50);
        |    }
        |  }
        |  try {
        |    let data = {};
        |    try {
        |      const text = readFileSync(file, "utf8").replace(/^\uFEFF/, "");
        |      if (text.trim()) data = JSON.parse(text);
        |    } catch (error) {
        |      if (error?.code !== "ENOENT") throw new Error("auth.json could not be read");
        |    }
        |    if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error("auth.json could not be read");
        |    data[id] = credential;
        |    const temp = file + "." + process.pid + "." + Date.now() + ".tmp";
        |    writeFileSync(temp, JSON.stringify(data, null, 2), { mode: 0o600 });
        |    renameSync(temp, file);
        |  } finally {
        |    try { rmdirSync(lock); } catch {}
        |  }
        |};
        |
        |const describe = async (registry) => {
        |  try { await registry.refresh?.({ allowNetwork: false }); } catch {}
        |  const available = registry.getAvailable();
        |  const all = registry.getAll();
        |  const registered = new Set(registry.getRegisteredProviderIds?.() ?? []);
        |  const ids = new Set([...registered, ...available.map((m) => m.provider)]);
        |  const model = (m) => ({ id: m.id, name: m.name ?? m.id, reasoning: !!m.reasoning, input: m.input ?? ["text"], contextWindow: m.contextWindow });
        |  return [...ids].map((id) => {
        |    const provider = registry.getProvider?.(id);
        |    const extension = registered.has(id);
        |    const usable = available.filter((m) => m.provider === id);
        |    const models = extension ? all.filter((m) => m.provider === id) : usable;
        |    const apiKey = provider?.auth?.apiKey;
        |    const oauth = provider?.auth?.oauth;
        |    return {
        |      id,
        |      name: provider?.name ?? registry.getProviderDisplayName?.(id) ?? id,
        |      extension,
        |      configured: usable.length > 0,
        |      ...(apiKey ? { apiKey: { name: apiKey.name ?? "API key", env: extension ? envNames(registry.getRegisteredProviderConfig?.(id)?.apiKey) : [] } } : {}),
        |      ...(oauth ? { oauth: { name: oauth.name ?? "Sign in", ...(oauth.loginLabel ? { loginLabel: oauth.loginLabel } : {}) } } : {}),
        |      models: models.map(model),
        |      available: usable.map((m) => m.id),
        |    };
        |  });
        |};
        |
        |const login = async (ctx, id, method, authFile) => {
        |  const provider = ctx.modelRegistry.getProvider?.(id);
        |  const auth = method === "api_key" ? provider?.auth?.apiKey : provider?.auth?.oauth;
        |  if (!auth?.login) throw new Error("This provider has no sign-in");
        |  const controller = new AbortController();
        |  const cancelled = () => new Error("Sign-in cancelled");
        |  const status = (event) => ctx.ui.setStatus("$LOGIN_STATUS", event === undefined ? undefined : JSON.stringify(event));
        |  const interaction = {
        |    signal: controller.signal,
        |    prompt: async (prompt) => {
        |      const opts = prompt.signal ? { signal: prompt.signal } : undefined;
        |      if (prompt.type === "select") {
        |        const labels = prompt.options.map((o) => o.label);
        |        const picked = await ctx.ui.select(prompt.message, labels, opts);
        |        const index = picked === undefined ? -1 : labels.indexOf(picked);
        |        if (index >= 0) return prompt.options[index].id;
        |      } else {
        |        const value = await ctx.ui.input(prompt.message, prompt.placeholder, opts);
        |        if (value !== undefined) return value;
        |      }
        |      // Closed by the flow itself (its callback came first) or cancelled by the user.
        |      if (prompt.signal?.aborted) throw new Error("Prompt closed");
        |      controller.abort();
        |      throw cancelled();
        |    },
        |    notify: (event) => status(event),
        |  };
        |  try {
        |    const credential = await auth.login(interaction, { agentName: "Pi-Bolt" });
        |    if (controller.signal.aborted) throw cancelled();
        |    await saveCredential(authFile, id, credential);
        |    try { await ctx.modelRegistry.refresh?.({ allowNetwork: false }); } catch {}
        |  } finally {
        |    status(undefined);
        |  }
        |};
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
        |        } else if (action === "providers") {
        |          write({ ok: true, providers: await describe(ctx.modelRegistry) });
        |        } else if (action === "login") {
        |          await login(ctx, rest[0], rest[1], rest[2]);
        |          write({ ok: true });
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
    suspend fun run(controller: SessionController, tmpDir: File, action: String, args: String = ""): Result<JsonObject> =
        runWith(tmpDir, action, args) { message ->
            val response = controller.request("prompt", timeoutMs = 60_000) { put("message", message) }
            if (response.success) null else response.error ?: "The engine refused the command"
        }

    /**
     * Runs the helper's [action] through [prompt], which sends the prompt and returns null once the engine answered it,
     * else why not (an engine the app keeps for itself: [opensec.pibolt.protocol.backend.LocalBackend.query]).
     */
    suspend fun runWith(tmpDir: File, action: String, args: String = "", prompt: suspend (String) -> String?): Result<JsonObject> {
        tmpDir.mkdirs()
        val out = File(tmpDir, "pibolt-app-${UUID.randomUUID()}.json")
        try {
            val message = "/$HIDDEN_COMMAND ${out.absolutePath} $action $args".trimEnd()
            prompt(message)?.let { return Result.failure(IllegalStateException(it)) }
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
