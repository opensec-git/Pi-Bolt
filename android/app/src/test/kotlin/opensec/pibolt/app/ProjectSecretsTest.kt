package opensec.pibolt.app

import opensec.pibolt.app.secrets.ProjectSecrets
import opensec.pibolt.app.secrets.Secret
import opensec.pibolt.app.secrets.SecretBox
import opensec.pibolt.app.secrets.SecretEnv
import opensec.pibolt.app.terminal.shellEnvironment
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectSecretsTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = SecretBox { key }

    private fun withDirs(block: (store: File, projects: File) -> Unit) {
        val root = Files.createTempDirectory("secrets").toFile()
        try {
            block(File(root, "store"), File(root, "projects").apply { mkdirs() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun roundTripKeepsOrderAndNeverStoresPlainText() = withDirs { store, projects ->
        val secrets = ProjectSecrets(store, box)
        val app = File(projects, "app").apply { mkdirs() }
        secrets.put(app, "STRIPE_KEY", "sk_test_\"quoted\" \$HOME\nline2 ünïcode")
        secrets.put(app, "DATABASE_URL", "postgres://u:p@localhost/db")
        secrets.put(app, "STRIPE_KEY", "sk_test_2")
        assertEquals(listOf(Secret("STRIPE_KEY", "sk_test_2"), Secret("DATABASE_URL", "postgres://u:p@localhost/db")), secrets.read(app))

        val files = store.listFiles().orEmpty()
        assertEquals(1, files.size)
        val raw = files[0].readBytes().decodeToString(throwOnInvalidSequence = false)
        assertFalse("postgres" in raw || "DATABASE_URL" in raw || "sk_test" in raw)

        // A fresh store with the same key (an app restart) opens it.
        assertEquals(mapOf("STRIPE_KEY" to "sk_test_2", "DATABASE_URL" to "postgres://u:p@localhost/db"), ProjectSecrets(store, box).environment(app))

        secrets.remove(app, "STRIPE_KEY")
        secrets.remove(app, "DATABASE_URL")
        assertTrue(store.listFiles().orEmpty().isEmpty())
        assertEquals(emptyMap(), secrets.environment(app))
    }

    @Test
    fun aDeletedProjectsSecretsDoNotPassToANewProjectOfTheSameName() = withDirs { store, projects ->
        val secrets = ProjectSecrets(store, box)
        val app = File(projects, "app").apply { mkdirs() }
        secrets.put(app, "STRIPE_KEY", "sk_live_old")
        // Deleting the project (Projects.delete) forgets its secrets; "app" made again starts empty.
        app.deleteRecursively()
        secrets.forget(app)
        File(projects, "app").mkdirs()
        assertFalse(secrets.hasAny(File(projects, "app")))
        assertEquals(emptyMap(), secrets.environment(File(projects, "app")))
        assertTrue(store.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aBlobOpensOnlyForItsProjectAndKey() = withDirs { store, projects ->
        val a = File(projects, "a").apply { mkdirs() }
        val b = File(projects, "b").apply { mkdirs() }
        val secrets = ProjectSecrets(store, box)
        secrets.put(a, "TOKEN", "for-a")
        val sealed = box.seal("x".encodeToByteArray(), ProjectSecrets.keyOf(a).toByteArray())
        assertFailsWith<Exception> { box.open(sealed, ProjectSecrets.keyOf(b).toByteArray()) }
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(emptyMap(), ProjectSecrets(store, SecretBox { otherKey }).environment(a))
        assertEquals(emptyMap(), secrets.environment(b))
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { box.open(tampered, ProjectSecrets.keyOf(a).toByteArray()) }
    }

    @Test
    fun anEngineServesAnotherProjectOnlyWhenNeitherHasSecrets() = withDirs { store, projects ->
        val secrets = ProjectSecrets(store, box)
        val plain = File(projects, "plain").apply { mkdirs() }
        val other = File(projects, "other").apply { mkdirs() }
        val keyed = File(projects, "keyed").apply { mkdirs() }
        secrets.put(keyed, "TOKEN", "t")
        assertTrue(secrets.engineFits(null, keyed))
        assertTrue(secrets.engineFits(plain.path, other))
        assertTrue(secrets.engineFits(keyed.path, keyed))
        // A spare started in a project with secrets would carry them elsewhere; one started elsewhere lacks them.
        assertFalse(secrets.engineFits(keyed.path, plain))
        assertFalse(secrets.engineFits(plain.path, keyed))
    }

    @Test
    fun theUserAndEnginePathsNameTheSameProject() {
        assertEquals("/data/data/in.opensec.pibolt/files/home/projects/app", ProjectSecrets.keyOf(File("/data/user/0/in.opensec.pibolt/files/home/projects/app/")))
        assertEquals("/data/data/in.opensec.pibolt/files/home/projects/app", ProjectSecrets.keyOf(File("/data/data/in.opensec.pibolt/files/home/projects/./app")))
    }

    @Test
    fun masksNeverRevealValueOrLength() {
        val masked = SecretEnv.mask("hunter2")
        assertEquals(SecretEnv.MASK, masked)
        assertEquals(SecretEnv.MASK, SecretEnv.mask("a-much-longer-secret-value-123456"))
        assertEquals("", SecretEnv.mask(""))
        assertFalse("hunter2" in Secret("PASS", "hunter2").toString())
        assertFalse("hunter2" in listOf(Secret("PASS", "hunter2")).toString())
    }

    @Test
    fun namesAreValidatedAndEngineVariablesProtected() {
        assertNull(SecretEnv.nameProblem("OPENAI_API_KEY"))
        assertNull(SecretEnv.nameProblem("_private1"))
        assertNotNull(SecretEnv.nameProblem(""))
        assertNotNull(SecretEnv.nameProblem("1ABC"))
        assertNotNull(SecretEnv.nameProblem("WITH-DASH"))
        for (reserved in listOf("PATH", "HOME", "SHELL", "LD_PRELOAD", "LD_LIBRARY_PATH", "XDG_CONFIG_HOME", "SSL_CERT_FILE", "GIT_CONFIG_COUNT", "PIBOLT_GITHUB_TOKEN")) {
            assertNotNull(SecretEnv.nameProblem(reserved), reserved)
        }
    }

    @Test
    fun injectionAddsSecretsWithoutReplacingTheEngineEnvironment() {
        val engine = mapOf("PATH" to "/p/bin:/system/bin", "HOME" to "/h", "TERM" to "dumb")
        val injected = SecretEnv.inject(engine, mapOf("API_TOKEN" to "t0k", "PATH" to "/evil", "LD_PRELOAD" to "/evil.so"))
        assertEquals(mapOf("PATH" to "/p/bin:/system/bin", "HOME" to "/h", "TERM" to "dumb", "API_TOKEN" to "t0k"), injected)
        assertTrue(SecretEnv.inject(engine, emptyMap()) === engine)
    }

    @Test
    fun theTerminalGetsTheEngineEnvironmentATerminalTypeAndTheSecrets() {
        val engine = mapOf("PATH" to "/p/bin:/system/bin", "HOME" to "/h", "TERM" to "dumb", "SSL_CERT_FILE" to "/p/etc/ssl/certs/ca-certificates.crt")
        val env = shellEnvironment(engine, mapOf("API_TOKEN" to "t0k", "TERM" to "vt100"))
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("truecolor", env["COLORTERM"])
        assertEquals("/p/bin:/system/bin", env["PATH"])
        assertEquals("/p/etc/ssl/certs/ca-certificates.crt", env["SSL_CERT_FILE"])
        assertEquals("t0k", env["API_TOKEN"])
    }

    @Test
    fun dotEnvEscapesAndGitignoreGainsDotEnvOnce() = withDirs { _, projects ->
        val secrets = listOf(Secret("A", "plain"), Secret("B", "q\"u\\o\$te\nnl"))
        val text = SecretEnv.dotEnv(secrets)
        assertTrue("A=\"plain\"\n" in text)
        assertTrue("B=\"q\\\"u\\\\o\\\$te\\nnl\"\n" in text)

        val noGit = File(projects, "nogit").apply { mkdirs() }
        assertFalse(SecretEnv.writeDotEnv(noGit, secrets))
        assertTrue(File(noGit, ".env").isFile)
        assertFalse(File(noGit, ".gitignore").exists())

        val repo = File(projects, "repo").apply { mkdirs() }
        File(repo, ".git").mkdirs()
        File(repo, ".gitignore").writeText("node_modules")
        assertTrue(SecretEnv.writeDotEnv(repo, secrets))
        assertEquals("node_modules\n.env\n", File(repo, ".gitignore").readText())
        assertFalse(SecretEnv.writeDotEnv(repo, secrets))
        assertEquals("node_modules\n.env\n", File(repo, ".gitignore").readText())
        assertTrue(SecretEnv.ignoresDotEnv("dist/\n  /.env  \n"))
        assertFalse(SecretEnv.ignoresDotEnv(".envrc\nsrc/.env\n"))
    }
}
