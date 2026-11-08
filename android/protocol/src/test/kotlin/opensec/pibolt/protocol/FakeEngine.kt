package opensec.pibolt.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * A stand-in for `pi --mode rpc`, run as a child JVM by LocalBackendTest. It speaks the RPC records the backend relies
 * on: get_state, get_messages, get_commands, prompt (with events), session switches, bash, extension dialogs.
 *
 * Environment: FAKE_CONTINUE_FILE (session that `--continue` opens), FAKE_EXIT_AT_START (exit code before answering),
 * FAKE_START_DELAY_MS.
 */
private val out = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), false, "UTF-8")
private var sessionFile = ""
private var messages = mutableListOf<JsonElement>()
private var newCount = 0

private fun emit(json: JsonObject) {
    synchronized(out) {
        out.print(json.toString())
        out.print("\n")
        out.flush()
    }
}

private fun respond(id: String?, command: String, data: JsonElement? = null, error: String? = null) = emit(
    buildJsonObject {
        id?.let { put("id", it) }
        put("type", "response")
        put("command", command)
        put("success", error == null)
        data?.let { put("data", it) }
        error?.let { put("error", it) }
    },
)

private fun text(role: String, text: String) = buildJsonObject {
    put("role", role)
    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
}

private fun event(type: String, fields: JsonObjectBuilder.() -> Unit = {}) = emit(buildJsonObject { put("type", type); fields() })

private fun fresh() {
    newCount++
    sessionFile = "/fake/new-$newCount.jsonl"
    messages = mutableListOf()
}

fun main(args: Array<String>) {
    System.err.println("fake engine starting ${args.joinToString(" ")}")
    System.getenv("FAKE_START_DELAY_MS")?.toLongOrNull()?.let { Thread.sleep(it) }
    System.getenv("FAKE_EXIT_AT_START")?.toIntOrNull()?.let {
        System.err.println("fake engine: failing on purpose")
        exitProcess(it)
    }
    val sessionArg = args.indexOf("--session").takeIf { it >= 0 }?.let { args[it + 1] }
    val continueFile = System.getenv("FAKE_CONTINUE_FILE") ?: "/fake/continued.jsonl"
    when {
        // The app resolves "continue" to the newest session itself and opens it with --session.
        sessionArg != null && sessionArg == continueFile -> {
            sessionFile = sessionArg
            messages = mutableListOf(text("user", "hello"), text("assistant", "hi"))
        }
        sessionArg != null -> {
            sessionFile = sessionArg
            messages = mutableListOf(text("user", "resumed"), text("assistant", "welcome back"))
        }
        "--continue" in args -> {
            sessionFile = continueFile
            messages = mutableListOf(text("user", "hello"), text("assistant", "hi"))
        }
        else -> fresh()
    }
    val input = System.`in`.bufferedReader(Charsets.UTF_8)
    while (true) {
        val line = input.readLine() ?: break
        val command = ProtocolJson.parseToJsonElement(line) as JsonObject
        val id = command.str("id")
        when (val type = command.type) {
            "get_state" -> respond(
                id,
                type,
                buildJsonObject {
                    put("sessionFile", sessionFile)
                    put("sessionId", sessionFile.substringAfterLast('/'))
                    put("messageCount", messages.size)
                    put("isStreaming", false)
                    put("isCompacting", false)
                    put("thinkingLevel", "off")
                    putJsonArray("args") { args.forEach { add(it) } }
                },
            )
            "get_messages" -> respond(id, type, buildJsonObject { put("messages", JsonArray(messages.toList())) })
            "get_commands" -> respond(
                id,
                type,
                buildJsonObject { put("commands", buildJsonArray { add(buildJsonObject { put("name", "skill:fake"); put("source", "skill") }) }) },
            )
            "prompt" -> {
                val message = command.str("message").orEmpty()
                when (message) {
                    "crash" -> {
                        System.err.println("fake engine: crashing")
                        exitProcess(7)
                    }
                    "dialog" -> {
                        respond(id, type, buildJsonObject { put("disposition", "handled") })
                        event("extension_ui_request") {
                            put("id", "d1")
                            put("method", "confirm")
                            put("title", "Sure?")
                        }
                    }
                    // A sheet of the mobile UI kit, then an update of it.
                    "sheet" -> {
                        respond(id, type, buildJsonObject { put("disposition", "handled") })
                        event("extension_ui_request") {
                            put("id", "s1")
                            put("method", "showView")
                            put("viewKey", "settings")
                            put("rev", 1)
                            putJsonObject("view") {
                                put("v", 1)
                                putJsonArray("body") { add(buildJsonObject { put("type", "input"); put("id", "branch"); put("label", "Branch"); put("value", "main") }) }
                            }
                        }
                        event("extension_ui_request") {
                            put("id", "u1")
                            put("method", "updateView")
                            put("viewKey", "settings")
                            put("rev", 2)
                            putJsonArray("ops") {
                                add(buildJsonObject { put("op", "set"); put("id", "branch"); putJsonObject("props") { put("value", "next") } })
                            }
                        }
                    }
                    "slow" -> Thread {
                        Thread.sleep(800)
                        respond(id, type, buildJsonObject { put("disposition", "late") })
                    }.start()
                    else -> {
                        respond(id, type, buildJsonObject { put("disposition", "started") })
                        val reply = if (message == "big") "x".repeat(3_000_000) + " end" else "echo: $message"
                        messages += text("user", message)
                        event("agent_start")
                        event("message_start") { put("message", text("user", message)) }
                        event("message_end") { put("message", text("user", message)) }
                        event("message_start") { put("message", buildJsonObject { put("role", "assistant"); put("content", JsonArray(emptyList())) }) }
                        event("message_update") {
                            putJsonObject("assistantMessageEvent") {
                                put("type", "text_delta")
                                put("contentIndex", 0)
                                put("delta", reply)
                            }
                        }
                        event("message_end") { put("message", text("assistant", reply)) }
                        messages += text("assistant", reply)
                        event("agent_settled")
                    }
                }
            }
            "new_session" -> {
                fresh()
                respond(id, type, buildJsonObject { put("cancelled", false) })
            }
            "switch_session" -> {
                sessionFile = command.str("sessionPath").orEmpty()
                messages = mutableListOf(text("user", "switched"))
                respond(id, type, buildJsonObject { put("cancelled", false) })
            }
            "bash" -> {
                event("bash_execution_update") {
                    id?.let { put("id", it) }
                    put("output", "partial")
                }
                respond(id, type, buildJsonObject { put("output", "ran: ${command.str("command")}"); put("exitCode", 0) })
            }
            "extension_ui_response" -> event("extension_ui_request") {
                put("id", "n-${command.str("id")}")
                put("method", "notify")
                put("message", "answered ${command.str("id")} ${command.bool("confirmed")}")
            }
            else -> respond(id, type ?: "parse", error = "Unknown command: $type")
        }
    }
    System.err.println("fake engine: stdin closed")
    exitProcess(0)
}
