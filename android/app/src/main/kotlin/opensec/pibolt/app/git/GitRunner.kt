package opensec.pibolt.app.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** One git run: its exit code (-1 when it timed out or could not start), stdout and stderr. */
class GitResult(val exit: Int, val out: String, val err: String, val timedOut: Boolean = false) {
    val ok: Boolean get() = exit == 0
}

/** A git invocation that failed; [message] is already explained for the user (see [GitErrors]). */
class GitException(message: String, val detail: String = "") : Exception(message)

/** Runs git. The app's implementation is [ProcessGitRunner]; tests use the host's git through it too. */
fun interface GitRunner {
    /**
     * Runs `git [args]` in [cwd]. [stdin] is written and closed first; [onProgress] gets each stderr line as it arrives
     * (git ends progress lines with `\r`). Never throws for a non-zero exit; check [GitResult.exit].
     */
    suspend fun run(cwd: File, args: List<String>, timeoutMs: Long, stdin: String?, onProgress: ((String) -> Unit)?): GitResult
}

suspend fun GitRunner.run(cwd: File, vararg args: String, timeoutMs: Long = GitRunners.LOCAL_TIMEOUT_MS): GitResult =
    run(cwd, args.toList(), timeoutMs, null, null)

object GitRunners {
    /** Local commands (status, diff, commit, switch). */
    const val LOCAL_TIMEOUT_MS = 30_000L

    /** Network commands (clone, fetch, pull, push). */
    const val NETWORK_TIMEOUT_MS = 15 * 60_000L

    /** Output kept per stream; a diff larger than this is cut (the UI says so). */
    const val MAX_OUTPUT = 8 shl 20
}

/**
 * Runs [git] as a child process on [Dispatchers.IO] with [environment] (the engine's, see EngineEnvironment), reading
 * stdout and stderr on their own threads so neither pipe fills up. A run that outlives its timeout is killed.
 * `GIT_TERMINAL_PROMPT=0` and no askpass: git never waits for input the app cannot give.
 */
class ProcessGitRunner(private val git: suspend () -> File?, private val environment: suspend () -> Map<String, String>) : GitRunner {
    override suspend fun run(cwd: File, args: List<String>, timeoutMs: Long, stdin: String?, onProgress: ((String) -> Unit)?): GitResult {
        return withContext(Dispatchers.IO) {
            // Both may set up the engine prefix (disk work) the first time, so they run on IO too.
            val binary = git() ?: throw GitException("Git is not available until the engine is set up")
            val env = environment()
            val process = try {
                ProcessBuilder(listOf(binary.path) + args).directory(cwd).apply {
                    val e = environment()
                    e.clear()
                    e.putAll(env)
                    e["GIT_TERMINAL_PROMPT"] = "0"
                    e.remove("GIT_ASKPASS")
                    e.remove("SSH_ASKPASS")
                    e["GIT_PAGER"] = "cat"
                    e["LC_ALL"] = "C.UTF-8"
                }.start()
            } catch (e: Exception) {
                throw GitException("Could not run git", e.message.orEmpty())
            }
            val out = ByteArrayOutputStream()
            val err = StringBuilder()
            val outThread = Thread({ copyCapped(process.inputStream, out) }, "git-out").apply {
                isDaemon = true
                start()
            }
            val errThread = Thread({ readLines(process.errorStream, err, onProgress) }, "git-err").apply {
                isDaemon = true
                start()
            }
            try {
                process.outputStream.use { s -> if (stdin != null) s.write(stdin.toByteArray()) }
            } catch (_: Exception) {
                // The process exited before reading its input; its exit code tells the rest.
            }
            val finished = try {
                runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            outThread.join(2_000)
            errThread.join(2_000)
            val errText = synchronized(err) { err.toString() }
            val outText = String(out.toByteArray(), Charsets.UTF_8)
            if (!finished) GitResult(-1, outText, errText, timedOut = true) else GitResult(process.exitValue(), outText, errText)
        }
    }

    private fun copyCapped(input: InputStream, out: ByteArrayOutputStream) {
        val buffer = ByteArray(64 shl 10)
        runCatching {
            input.use {
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    val room = GitRunners.MAX_OUTPUT - out.size()
                    if (room > 0) out.write(buffer, 0, minOf(n, room))
                }
            }
        }
    }

    private fun readLines(input: InputStream, into: StringBuilder, onProgress: ((String) -> Unit)?) {
        val line = StringBuilder()
        runCatching {
            input.bufferedReader().use { reader ->
                while (true) {
                    val c = reader.read()
                    if (c < 0) break
                    val ch = c.toChar()
                    if (ch == '\r' || ch == '\n') {
                        if (line.isNotEmpty()) {
                            val text = line.toString()
                            onProgress?.invoke(text)
                            // Progress lines repeat with new counts: keep only finished lines in the text.
                            if (ch == '\n') synchronized(into) { if (into.length < GitRunners.MAX_OUTPUT) into.append(text).append('\n') }
                            line.setLength(0)
                        }
                    } else {
                        line.append(ch)
                    }
                }
                if (line.isNotEmpty()) synchronized(into) { into.append(line) }
            }
        }
    }
}
