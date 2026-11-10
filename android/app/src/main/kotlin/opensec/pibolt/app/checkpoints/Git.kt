package opensec.pibolt.app.checkpoints

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A git command failed: its arguments and what it printed on stderr. */
class GitException(message: String) : IOException(message)

/**
 * Runs git against the shadow repository [gitDir] with [workTree] as its work tree (GIT_DIR and GIT_WORK_TREE), so the
 * project's own `.git` is never read or written. [executable] is the pack's git on the phone, the host's in tests.
 *
 * The environment is [baseEnv] without the caller's GIT_* variables (a hook or another repository's GIT_INDEX_FILE must
 * not leak in), with user and system config ignored: settings such as autocrlf, hooks or a pager change nothing here.
 */
class Git(
    private val executable: String,
    /** Read at each run (the phone's pack may still be setting up when this is made). */
    private val baseEnv: () -> Map<String, String>,
    val gitDir: File,
    val workTree: File,
) {
    class Result(val code: Int, val out: ByteArray, val err: String) {
        val ok: Boolean get() = code == 0
        val text: String get() = String(out, Charsets.UTF_8)
    }

    private fun env(): Map<String, String> = baseEnv().filterKeys { !it.startsWith("GIT_") || it in KEEP }.toMutableMap().apply {
        put("GIT_DIR", gitDir.path)
        put("GIT_WORK_TREE", workTree.path)
        put("GIT_CONFIG_NOSYSTEM", "1")
        put("GIT_CONFIG_GLOBAL", NULL_DEVICE)
        put("GIT_TERMINAL_PROMPT", "0")
        put("GIT_OPTIONAL_LOCKS", "0")
        put("GIT_AUTHOR_NAME", AUTHOR)
        put("GIT_AUTHOR_EMAIL", EMAIL)
        put("GIT_COMMITTER_NAME", AUTHOR)
        put("GIT_COMMITTER_EMAIL", EMAIL)
    }

    /**
     * Runs `git [args]`, feeding [stdin]. [literal] makes every pathspec a plain path (no `*` or `:(magic)`), as for
     * paths that come from a listing. stdout is kept up to [maxOut] bytes (the rest is read and dropped).
     */
    fun run(args: List<String>, stdin: ByteArray? = null, literal: Boolean = false, maxOut: Int = MAX_OUT, timeoutMs: Long = TIMEOUT_MS): Result {
        val process = try {
            ProcessBuilder(listOf(executable) + args).directory(workTree).apply {
                environment().clear()
                environment().putAll(env())
                if (literal) environment()["GIT_LITERAL_PATHSPECS"] = "1"
            }.start()
        } catch (e: IOException) {
            throw GitException("git could not start: ${e.message}")
        }
        val err = ByteArrayOutputStream()
        val errReader = thread(name = "git-stderr", isDaemon = true) { drain(process.errorStream, err, MAX_ERR) }
        val writer = thread(name = "git-stdin", isDaemon = true) {
            try {
                process.outputStream.use { if (stdin != null) it.write(stdin) }
            } catch (_: IOException) {
                // git exited before reading everything; its exit code says why.
            }
        }
        val out = ByteArrayOutputStream()
        val outReader = thread(name = "git-stdout", isDaemon = true) { drain(process.inputStream, out, maxOut) }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw GitException("git ${args.firstOrNull()} timed out")
        }
        outReader.join(JOIN_MS)
        errReader.join(JOIN_MS)
        writer.join(JOIN_MS)
        return Result(process.exitValue(), out.toByteArray(), String(err.toByteArray(), Charsets.UTF_8).trim())
    }

    /** [run] that throws [GitException] unless git exits with 0. */
    fun check(args: List<String>, stdin: ByteArray? = null, literal: Boolean = false, maxOut: Int = MAX_OUT): Result {
        val result = run(args, stdin, literal, maxOut)
        if (!result.ok) throw GitException("git ${args.firstOrNull()} failed (${result.code}): ${result.err.take(400)}")
        return result
    }

    private fun drain(input: InputStream, to: ByteArrayOutputStream, max: Int) {
        val buffer = ByteArray(64 * 1024)
        try {
            input.use {
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    val room = max - to.size()
                    if (room > 0) to.write(buffer, 0, minOf(n, room))
                }
            }
        } catch (_: IOException) {
            // The process was killed.
        }
    }

    companion object {
        private val KEEP = setOf("GIT_EXEC_PATH", "GIT_TEMPLATE_DIR")
        private const val AUTHOR = "Pi-Bolt checkpoints"
        private const val EMAIL = "checkpoints@pi-bolt.invalid"
        private const val NULL_DEVICE = "/dev/null"
        private const val MAX_OUT = 64 * 1024 * 1024
        private const val MAX_ERR = 16 * 1024
        private const val TIMEOUT_MS = 120_000L
        private const val JOIN_MS = 5_000L

        /** NUL-separated git output (`-z`) as strings. */
        fun splitZ(out: ByteArray): List<String> {
            val parts = ArrayList<String>()
            var start = 0
            for (i in out.indices) {
                if (out[i] == 0.toByte()) {
                    parts += String(out, start, i - start, Charsets.UTF_8)
                    start = i + 1
                }
            }
            if (start < out.size) parts += String(out, start, out.size - start, Charsets.UTF_8)
            return parts
        }

        /** Paths as NUL-separated stdin for `--pathspec-from-file=- --pathspec-file-nul`. */
        fun joinZ(paths: Collection<String>): ByteArray {
            val out = ByteArrayOutputStream()
            for (p in paths) {
                out.write(p.toByteArray(Charsets.UTF_8))
                out.write(0)
            }
            return out.toByteArray()
        }
    }
}
