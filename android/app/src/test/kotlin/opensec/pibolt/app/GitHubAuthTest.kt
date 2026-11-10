package opensec.pibolt.app

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import opensec.pibolt.app.git.GitErrors
import opensec.pibolt.app.git.github.AesGcmBox
import opensec.pibolt.app.git.github.DeviceFlow
import opensec.pibolt.app.git.github.DeviceFlowState
import opensec.pibolt.app.git.github.GitCredentialBroker
import opensec.pibolt.app.git.github.GitCredentials
import opensec.pibolt.app.git.github.GitHub
import opensec.pibolt.app.git.github.GitHubException
import opensec.pibolt.app.git.github.GitHubState
import opensec.pibolt.app.git.github.KeyValue
import opensec.pibolt.app.git.github.Redact
import opensec.pibolt.app.git.github.TokenKind
import opensec.pibolt.app.git.github.TokenStore
import org.junit.After
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubAuthTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val sleeps = ArrayList<Long>()
    private val token = "gho_" + "Zx9".repeat(12)

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").apply { headers.forEach { (k, v) -> addHeader(k, v) } }.body(body).build()

    private fun flow(): DeviceFlow {
        server.start()
        return DeviceFlow(http, "Iv1.testclient", server.url("/").toString(), sleep = { sleeps += it })
    }

    private val code = """{"device_code":"dev123","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""

    @Test
    fun deviceFlowPollsThroughPendingAndSlowDownToAToken() = runBlocking {
        val f = flow()
        server.enqueue(json(code))
        server.enqueue(json("""{"error":"authorization_pending"}"""))
        server.enqueue(json("""{"error":"slow_down","interval":10}"""))
        server.enqueue(json("""{"error":"authorization_pending"}"""))
        server.enqueue(json("""{"access_token":"$token","token_type":"bearer","scope":"repo,workflow"}"""))

        val states = f.run(listOf("repo", "workflow")).toList()
        assertIs<DeviceFlowState.Requesting>(states[0])
        val show = assertIs<DeviceFlowState.ShowCode>(states[1])
        assertEquals("WDJB-MJHT", show.userCode)
        assertEquals("https://github.com/login/device", show.verificationUri)
        val done = assertIs<DeviceFlowState.Authorized>(states.last())
        assertEquals(token, done.token)
        assertEquals(listOf("repo", "workflow"), done.scopes)
        assertFalse(token in done.toString(), "the state's text never carries the token")
        // 5 s, 5 s, then slow_down raised it to 10 s for the rest.
        assertEquals(listOf(5_000L, 5_000L, 10_000L, 10_000L), sleeps)

        val first = server.takeRequest()
        assertEquals("/login/device/code", first.url.encodedPath)
        val firstBody = first.body!!.utf8()
        assertTrue("client_id=Iv1.testclient" in firstBody)
        assertTrue("scope=repo%20workflow" in firstBody || "scope=repo+workflow" in firstBody, firstBody)
        val poll = server.takeRequest()
        assertEquals("/login/oauth/access_token", poll.url.encodedPath)
        assertEquals("application/json", poll.headers["Accept"])
        val pollBody = poll.body!!.utf8()
        assertTrue("device_code=dev123" in pollBody)
        assertTrue("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code" in pollBody, pollBody)
    }

    @Test
    fun deviceFlowEndsOnDenialExpiryAndBadClient() = runBlocking {
        val f = flow()
        server.enqueue(json(code))
        server.enqueue(json("""{"error":"access_denied"}"""))
        val denied = assertIs<DeviceFlowState.Failed>(f.run(listOf("repo")).toList().last())
        assertTrue("cancelled" in denied.message)
        assertTrue(denied.canRetry)

        server.enqueue(json(code))
        server.enqueue(json("""{"error":"expired_token"}"""))
        assertTrue("expired" in assertIs<DeviceFlowState.Failed>(f.run(listOf("repo")).toList().last()).message)

        server.enqueue(json("""{"error":"device_flow_disabled","error_description":"Device Flow must be explicitly enabled"}""", code = 400))
        val disabled = f.run(listOf("repo")).toList()
        assertEquals(2, disabled.size, "no code is shown when the app is misconfigured")
        val failed = assertIs<DeviceFlowState.Failed>(disabled.last())
        assertFalse(failed.canRetry)
        assertTrue("Device flow is not enabled" in failed.message)
    }

    @Test
    fun deviceFlowGivesUpWhenTheCodeExpires() = runBlocking {
        val f = flow()
        server.enqueue(json("""{"device_code":"d","user_code":"U","verification_uri":"https://github.com/login/device","expires_in":10,"interval":5}"""))
        server.enqueue(json("""{"error":"authorization_pending"}"""))
        server.enqueue(json("""{"error":"authorization_pending"}"""))
        val last = assertIs<DeviceFlowState.Failed>(f.run(listOf("repo")).toList().last())
        assertTrue("expired" in last.message)
        assertEquals(listOf(5_000L, 5_000L), sleeps)
    }

    /** A key-value store that remembers everything written to it, like SharedPreferences on disk. */
    private class Recording : KeyValue {
        val map = HashMap<String, String>()
        val writes = ArrayList<String>()

        override fun get(key: String) = map[key]

        override fun put(key: String, value: String?) {
            writes += "$key=$value"
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private fun box(): AesGcmBox {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmBox { key }
    }

    @Test
    fun signInWithAnAnswerThatIsNotAnAccountIsAGitHubErrorAndStoresNothing() = runBlocking {
        // A captive portal or proxy answering 200 with other JSON: the device-flow sheet shows an error (it catches
        // GitHubException), not a crash, and no account with an empty login is stored.
        server.start()
        val kv = Recording()
        val gh = GitHub(TokenStore(kv, box()), http, clientId = "", webBase = server.url("/").toString(), apiBase = server.url("/").toString())
        server.enqueue(json("[]"))
        assertFailsWith<GitHubException> { gh.signIn(token, TokenKind.Device) }
        server.enqueue(json("""{"message":"ok"}"""))
        assertFailsWith<GitHubException> { gh.signIn(token, TokenKind.Device) }
        assertIs<GitHubState.SignedOut>(gh.state.value)
        assertTrue(kv.writes.isEmpty())
    }

    @Test
    fun tokenIsStoredSealedAndNeverInTextOrLogs() = runBlocking {
        server.start()
        val kv = Recording()
        val store = TokenStore(kv, box())
        val sock = File(Files.createTempDirectory("pibolt-cred").toFile(), "cred/socket")
        var answered: Pair<String, String>? = null
        val gh = GitHub(
            store, http, clientId = "", webBase = server.url("/").toString(), apiBase = server.url("/").toString(),
            socketBroker = { credential -> GitCredentialBroker(sock, credential, { _, _ -> answered = credential(); Closeable {} }) },
        )
        assertFalse(gh.deviceFlowAvailable)

        // A rejected token: the error and everything written say nothing of it.
        server.enqueue(json("""{"message":"Bad credentials"}""", code = 401))
        val rejected = assertFailsWith<GitHubException> { gh.signIn("ghp_" + "Q".repeat(36), TokenKind.Personal) }
        assertFalse("ghp_" in rejected.message.orEmpty())
        assertIs<GitHubState.SignedOut>(gh.state.value)
        assertTrue(kv.writes.isEmpty())

        server.enqueue(json("""{"login":"octocat","name":"The Octocat","id":583231}""", headers = mapOf("X-OAuth-Scopes" to "repo, workflow")))
        val account = gh.signIn("  $token\n", TokenKind.Personal)
        assertEquals("octocat", account.login)
        assertEquals(listOf("repo", "workflow"), account.scopes)
        assertEquals("583231+octocat@users.noreply.github.com", account.noreplyEmail)
        server.takeRequest()
        assertEquals("Bearer $token", server.takeRequest().headers["Authorization"], "the token is sent trimmed")

        // On disk: only the sealed value, which is neither the token nor its plain base64.
        val plainB64 = Base64.getEncoder().encodeToString(token.toByteArray())
        for (w in kv.writes) {
            assertFalse(token in w, w)
            assertFalse(plainB64 in w, w)
        }
        assertEquals(token, store.token())
        assertNotNull(store.account())

        // Text the app may log or show.
        val texts = listOf(gh.toString(), store.toString(), gh.state.value.toString(), account.toString(), gh.api.toString())
        for (t in texts) assertFalse(token in t, t)
        assertFalse(token in Redact.text("push failed for https://x:$token@github.com/o/r", token))
        assertFalse(token in Redact.text("token $token"))
        assertFalse(token in GitErrors.explain("fatal: Authentication failed for 'https://github.com/o/r.git/'"))

        // The engine environment names the broker's socket and never carries the token; the broker answers with it.
        val env = gh.gitEnvironment()
        assertEquals(sock.path, env[GitCredentials.SOCKET_VAR])
        for ((k, v) in env) assertFalse(token in v, k)
        assertEquals(account.login to token, answered)
        val out = ByteArrayOutputStream()
        GitCredentialBroker.answer(ByteArrayInputStream("GET /github HTTP/1.1\r\nHost: pibolt\r\n\r\n".toByteArray()), out, answered)
        assertTrue(out.toString(Charsets.UTF_8.name()).endsWith("\r\n\r\nusername=${account.login}\npassword=$token\n"))

        // Another key cannot open it (a reset Keystore): signed out, no crash.
        assertNull(TokenStore(kv, box()).token())

        gh.signOut()
        assertIs<GitHubState.SignedOut>(gh.state.value)
        assertNull(store.token())
        assertTrue(kv.map.isEmpty())
        assertTrue(gh.gitEnvironment().isEmpty())
    }

    /** No log call in the git and GitHub code (or its screens) mentions the token or the credential environment. */
    @Test
    fun noLogStatementTouchesTheToken() {
        val roots = listOf("src/main/kotlin/opensec/pibolt/app/git", "src/main/kotlin/opensec/pibolt/app/ui/git").map(::File)
        val files = roots.filter { it.isDirectory }.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(files.isNotEmpty(), "sources found from ${File(".").absolutePath}")
        val logCall = Regex("""\b(Log\.[vdiwe]|println|printStackTrace|System\.(out|err))\b""")
        val secret = Regex("""token|gitEnvironment|TOKEN_VAR|password""", RegexOption.IGNORE_CASE)
        for (f in files) {
            f.readLines().forEachIndexed { i, line ->
                if (logCall.containsMatchIn(line)) assertFalse(secret.containsMatchIn(line), "${f.name}:${i + 1}: $line")
            }
        }
    }
}
