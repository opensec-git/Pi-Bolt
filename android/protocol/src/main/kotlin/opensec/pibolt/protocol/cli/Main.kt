package opensec.pibolt.protocol.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import opensec.pibolt.protocol.Base64Url
import opensec.pibolt.protocol.PairingPayload
import opensec.pibolt.protocol.backend.ConnectionState
import opensec.pibolt.protocol.backend.DisconnectReason
import opensec.pibolt.protocol.backend.RemoteBackend
import opensec.pibolt.protocol.backend.RemoteConfig
import opensec.pibolt.protocol.noise.KeyPair
import opensec.pibolt.protocol.rpc.RpcClient
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.type
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

/**
 * Interop test client: pair or reconnect with a host, print every message as one JSON line, optionally send a prompt.
 *
 * ```
 * ./gradlew :protocol:run --args='<pibolt://pair#...> [prompt text] [--steer|--follow-up] [--state DIR] [--quiet]'
 * ```
 * The device key and the list of paired hosts live in `--state` (default `~/.pibolt-rc-cli`), so a second run
 * reconnects without the pairing secret. With a prompt, the client exits after `agent_settled`.
 */
fun main(args: Array<String>) {
    var pairing: String? = null
    var prompt: String? = null
    var behavior: StreamingBehavior? = null
    var stateDir = File(System.getProperty("user.home"), ".pibolt-rc-cli")
    var quiet = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--steer" -> behavior = StreamingBehavior.Steer
            "--follow-up" -> behavior = StreamingBehavior.FollowUp
            "--quiet" -> quiet = true
            "--state" -> stateDir = File(args.getOrNull(++i) ?: usage())
            "-h", "--help" -> usage()
            else -> if (pairing == null) pairing = a else prompt = listOfNotNull(prompt, a).joinToString(" ")
        }
        i++
    }
    val payload = try {
        PairingPayload.parse(pairing ?: usage())
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(2)
    }
    stateDir.mkdirs()
    val identity = loadIdentity(File(stateDir, "device-key"))
    val pairedFile = File(stateDir, "paired-hosts")
    val paired = if (pairedFile.exists()) pairedFile.readLines().toSet() else emptySet()
    val alreadyPaired = payload.hostKeyId in paired
    if (!alreadyPaired && payload.isExpired()) System.err.println("warning: the pairing code has expired")
    System.err.println("device key ${Base64Url.encode(identity.publicKey)}; host ${payload.hostName} (${payload.hostKeyId}); ${if (alreadyPaired) "paired" else "pairing"}")

    val backend = RemoteBackend(
        RemoteConfig(
            hostKey = payload.hostKey,
            endpoints = payload.endpoints,
            identity = identity,
            deviceName = "pibolt-rc-cli on ${System.getProperty("os.name")}",
            appVersion = "cli",
            pairSecret = if (alreadyPaired) null else payload.secret,
        ),
        onPaired = { pairedFile.appendText(payload.hostKeyId + "\n") },
    )

    val code = runBlocking(Dispatchers.Default) {
        val rpc = RpcClient(backend, this)
        val stateJob = launch { backend.state.collect { System.err.println("state: $it") } }
        if (!backend.connect()) {
            stateJob.cancel()
            rpc.stop()
            return@runBlocking 1
        }
        if (alreadyPaired.not() && payload.hostKeyId !in (if (pairedFile.exists()) pairedFile.readLines() else emptyList())) {
            // Accepted without `paired: true`: the host already knew this device.
            pairedFile.appendText(payload.hostKeyId + "\n")
        }
        val settled = CompletableDeferred<Unit>()
        val hello = CompletableDeferred<Unit>()
        val events = launch {
            rpc.events.collect { event ->
                if (!quiet || event.type != "message_update") println(event)
                when (event.type) {
                    "remote_hello" -> hello.complete(Unit)
                    "agent_settled" -> settled.complete(Unit)
                    "extension_ui_request" -> if (event.str("method") in setOf("select", "confirm", "input", "editor")) {
                        System.err.println("dialog ${event.str("id")}: answer it in the terminal")
                    }
                }
            }
        }
        val disconnected = launch {
            backend.state.first { it is ConnectionState.Disconnected }
            settled.complete(Unit)
            hello.complete(Unit)
        }
        if (withTimeoutOrNull(15_000) { hello.await() } == null) System.err.println("warning: no remote_hello within 15 s")
        if (prompt != null) {
            val response = rpc.prompt(prompt, streamingBehavior = behavior)
            System.err.println("prompt: success=${response.success} ${response.data ?: response.error ?: ""}")
            if (response.success) settled.await()
            backend.close()
        } else {
            System.err.println("connected; printing events (Ctrl-C to stop)")
            backend.state.first { it is ConnectionState.Disconnected }
        }
        val last = backend.state.value
        events.cancel()
        disconnected.cancel()
        stateJob.cancel()
        rpc.stop()
        if (last is ConnectionState.Disconnected && last.reason != DisconnectReason.Closed) 1 else 0
    }
    exitProcess(code)
}

private fun usage(): Nothing {
    System.err.println("usage: pibolt-rc-cli '<pibolt://pair#...>' [prompt text] [--steer|--follow-up] [--state DIR] [--quiet]")
    exitProcess(2)
}

private fun loadIdentity(file: File): KeyPair {
    if (file.exists()) return KeyPair.fromPrivate(Base64Url.decode(file.readText()))
    val pair = KeyPair.generate()
    // Created 0600 before the key is written, so no umask-dependent window; plain write where POSIX modes do not exist.
    val created = runCatching {
        Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    }.isSuccess
    if (!created && file.exists()) throw IllegalStateException("$file appeared while creating it")
    file.writeText(Base64Url.encode(pair.privateKey))
    return pair
}
