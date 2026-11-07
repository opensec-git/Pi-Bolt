package opensec.pibolt.app.engine

import android.content.res.AssetManager
import android.system.Os
import android.system.OsConstants
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import opensec.pibolt.protocol.ProtocolJson
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Background shell jobs (the app's `pibolt-bg` extension, assets/extensions/pibolt-bg.js): the tools `bash_background`,
 * `bash_output`, `bash_kill` and `bash_jobs` in project sessions. Chats never get them (ChatSpace's `--tools`
 * allowlist). The extension mirrors its jobs to `$TMPDIR/pibolt-bg/<engine pid>/jobs.json` (logs beside it, `<id>.log`)
 * and sets the extension status [STATUS_KEY] while any runs, which keeps the session busy (SessionController) and the
 * foreground service up (SessionService). The app reads the file for the Background sheet and stops a job by signalling
 * its process group, as `bash_kill` does.
 */
object BackgroundJobs {
    const val FILE = "pibolt-bg.js"
    const val STATUS_KEY = "bg-jobs"
    val TOOLS = listOf("bash_background", "bash_output", "bash_kill", "bash_jobs")

    /** Ports that running jobs printed lately (a dev server's URL): hints for the Preview chip (Terminals.ports). */
    @Volatile
    var hintPorts: List<Int> = emptyList()

    data class Job(
        val id: Int,
        val name: String,
        val command: String,
        val pid: Int,
        val status: String,
        val exitCode: Int?,
        val signal: String?,
        val startedAt: Long,
        val endedAt: Long?,
        val bytes: Long,
        val logFile: String,
    ) {
        val running: Boolean get() = status == "running"

        val state: String
            get() = when {
                running -> "Running"
                signal != null -> "Stopped"
                exitCode == 0 -> "Done"
                else -> "Exited $exitCode"
            }
    }

    /** Writes the extension to `<agentDir>/extensions/` if missing or older (its first line). Blocking. */
    fun install(agentDir: File, assets: AssetManager): Boolean = runCatching {
        val source = assets.open("extensions/$FILE").use { it.reader().readText() }
        val dir = File(agentDir, "extensions").apply { mkdirs() }
        val file = File(dir, FILE)
        if (file.isFile && file.bufferedReader().use { it.readLine() } == source.lineSequence().first()) return true
        val temp = File(dir, ".$FILE.${System.nanoTime()}.tmp")
        temp.writeText(source)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrDefault(false)

    /** The jobs file of the engine [pid], under the engine's TMPDIR [tmp]. */
    fun stateFile(tmp: File, pid: Long): File = File(tmp, "pibolt-bg/$pid/jobs.json")

    /** The jobs in a jobs.json text; empty when it cannot be read. */
    fun parse(text: String): List<Job> = runCatching {
        val root = ProtocolJson.parseToJsonElement(text) as JsonObject
        (root["jobs"] as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            Job(
                id = l("id")?.toInt() ?: return@mapNotNull null,
                name = s("name").orEmpty(),
                command = s("command").orEmpty(),
                pid = l("pid")?.toInt() ?: -1,
                status = s("status") ?: "exited",
                exitCode = l("exitCode")?.toInt(),
                signal = s("signal"),
                startedAt = l("startedAt") ?: 0L,
                endedAt = l("endedAt"),
                bytes = l("bytes") ?: 0L,
                logFile = s("logFile").orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    fun read(file: File): List<Job> = if (file.isFile) parse(runCatching { file.readText() }.getOrDefault("")) else emptyList()

    /** The pill's text: "2 running", "1 running · 3 done", "3 done". */
    fun summary(jobs: List<Job>): String {
        val running = jobs.count { it.running }
        val ended = jobs.size - running
        return listOfNotNull(running.takeIf { it > 0 }?.let { "$it running" }, ended.takeIf { it > 0 }?.let { "$it ended" }).joinToString(" · ")
    }

    /** "12s", "3m 4s", "1h 2m". */
    fun uptime(job: Job, now: Long): String {
        val s = (((job.endedAt ?: now) - job.startedAt) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    /** The last [maxBytes] of a log, from its first whole line. Blocking. */
    fun tail(path: String, maxBytes: Int = 4096): String = runCatching {
        RandomAccessFile(path, "r").use { f ->
            val len = f.length()
            val start = (len - maxBytes).coerceAtLeast(0)
            val buf = ByteArray((len - start).toInt())
            f.seek(start)
            f.readFully(buf)
            val text = String(buf, Charsets.UTF_8)
            if (start > 0) text.substringAfter('\n') else text
        }
    }.getOrDefault("")

    /** Stops [job]'s process group: SIGTERM, SIGKILL after 5 s if the shell is still there. Blocking. */
    fun stop(job: Job) {
        if (job.pid <= 1) return
        runCatching { Os.kill(-job.pid, OsConstants.SIGTERM) }
        for (i in 0 until 50) {
            Thread.sleep(100)
            if (!File("/proc/${job.pid}").exists()) break
        }
        // Whatever of the group ignored SIGTERM (the shell may have ended before its children).
        runCatching { Os.kill(-job.pid, OsConstants.SIGKILL) }
    }
}
