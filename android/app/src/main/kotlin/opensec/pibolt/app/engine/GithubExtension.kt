package opensec.rapidpi.app.engine

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs the pibolt-github extension (asset extensions/pibolt-github.js) as
 * <agentDir>/extensions/pibolt-github.js, where Pi discovers global extensions.
 * Rewritten whenever the shipped text differs, so the tools always match this app's build.
 */
object GithubExtension {
    const val ASSET = "extensions/pibolt-github.js"
    const val FILE = "pibolt-github.js"

    /** Blocking; false when the asset cannot be read or the agent dir cannot be written. */
    fun install(agentDir: File, assets: AssetManager): Boolean = runCatching {
        val shipped = assets.open(ASSET).use { it.readBytes() }
        val dir = File(agentDir, "extensions").apply { mkdirs() }
        val file = File(dir, FILE)
        if (file.isFile && file.length() == shipped.size.toLong() && file.readBytes().contentEquals(shipped)) return true
        val temp = File(dir, ".$FILE.${System.nanoTime()}.tmp")
        temp.writeBytes(shipped)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrDefault(false)
}
