package opensec.rapidpi.app.git.github

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.Base64

@Immutable
data class GitHubRepo(
    val fullName: String,
    val name: String,
    val owner: String,
    val private: Boolean,
    val description: String?,
    val cloneUrl: String,
    val defaultBranch: String,
    val pushedAt: String?,
)

@Immutable
data class PullRequest(val number: Int, val url: String, val title: String)

@Immutable
data class GitHubRepoCreated(
    val fullName: String,
    val cloneUrl: String,
    val htmlUrl: String,
    val defaultBranch: String,
)

@Immutable
data class GitHubIssue(
    val number: Int,
    val title: String,
    val body: String?,
    val htmlUrl: String,
    val state: String,
)

@Immutable
data class GitHubComment(
    val id: Long,
    val body: String,
    val htmlUrl: String,
)

@Immutable
data class GitHubContent(
    val path: String,
    val sha: String,
    val size: Long,
    val content: String?,
    val downloadUrl: String?,
)

/** A GitHub call that failed, with a message for the user. [status] is the HTTP code (0 without an answer). */
class GitHubException(message: String, val status: Int) : Exception(message)

/** owner/repo of a GitHub remote URL (https, ssh or scp-like), or null for other hosts. */
object GitHubRemote {
    private val https = Regex("""^https?://(?:[^@/]+@)?github\.com/([^/\s]+)/([^/\s]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)
    private val ssh = Regex("""^ssh://(?:[^@/]+@)?github\.com(?::\d+)?/([^/\s]+)/([^/\s]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)
    private val scp = Regex("""^(?:[^@\s]+@)?github\.com:([^/\s]+)/([^/\s]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)

    fun parse(url: String): Pair<String, String>? {
        val u = url.trim()
        for (r in listOf(https, ssh, scp)) r.matchEntire(u)?.let { return it.groupValues[1] to it.groupValues[2] }
        return null
    }
}

/**
 * The few GitHub REST calls the app makes (api.github.com, version 2022-11-28): the signed-in user, their
 * repositories, a repository's default branch and creating a pull request. [token] is read per call and only ever goes
 * into the Authorization header.
 */
class GitHubApi(
    private val http: OkHttpClient,
    private val token: () -> String?,
    private val apiBase: String = "https://api.github.com",
) {
    /** The token's user and its OAuth scopes (`X-OAuth-Scopes`; empty for fine-grained tokens). */
    suspend fun user(withToken: String? = null): Pair<GitHubAccountInfo, List<String>> {
        val (json, headers) = call("GET", "/user", null, withToken)
        // A proxy or captive portal can answer 200 with other JSON: no account, not a crash or an empty login.
        val o = json as? JsonObject ?: throw GitHubException("GitHub sent an unexpected answer.", 200)
        val login = o.str("login")?.takeIf { it.isNotBlank() } ?: throw GitHubException("GitHub sent an unexpected answer.", 200)
        val scopes = headers["x-oauth-scopes"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return GitHubAccountInfo(login, o.str("name"), o.long("id") ?: 0) to scopes
    }

    /** The user's repositories, most recently pushed first, [perPage] per [page] (1-based). */
    suspend fun repos(page: Int = 1, perPage: Int = 50): List<GitHubRepo> {
        val (json, _) = call("GET", "/user/repos?per_page=$perPage&page=$page&sort=pushed&affiliation=owner,collaborator,organization_member", null)
        return (json as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::repoOf) }
    }

    suspend fun repo(owner: String, name: String): GitHubRepo {
        val (json, _) = call("GET", "/repos/$owner/$name", null)
        return repoOf(json.jsonObject) ?: throw GitHubException("GitHub sent an unexpected answer.", 200)
    }

    suspend fun createPull(owner: String, name: String, title: String, head: String, base: String, body: String): PullRequest {
        val payload = buildJsonObject {
            put("title", title)
            put("head", head)
            put("base", base)
            put("body", body)
        }
        val (json, _) = call("POST", "/repos/$owner/$name/pulls", payload.toString())
        val o = json.jsonObject
        return PullRequest(o.long("number")?.toInt() ?: 0, o.str("html_url").orEmpty(), o.str("title") ?: title)
    }

    /** Create a user repository (`gh repo create` equivalent). */
    suspend fun createRepo(
        name: String,
        description: String? = null,
        private: Boolean = true,
        autoInit: Boolean = false,
    ): GitHubRepoCreated {
        val payload = buildJsonObject {
            put("name", name)
            description?.let { put("description", it) }
            put("private", private)
            put("auto_init", autoInit)
        }
        val (json, _) = call("POST", "/user/repos", payload.toString())
        val o = json.jsonObject
        return GitHubRepoCreated(
            fullName = o.str("full_name") ?: throw GitHubException("GitHub sent an unexpected answer.", 200),
            cloneUrl = o.str("clone_url") ?: "https://github.com/${o.str("full_name") ?: "x"}.git",
            htmlUrl = o.str("html_url").orEmpty(),
            defaultBranch = o.str("default_branch") ?: "main",
        )
    }

    /** Read a file's contents from a repo (base64 decoded). Null when not found (404); other errors propagate. */
    suspend fun getContents(owner: String, repo: String, path: String, ref: String? = null): GitHubContent? {
        val url = "/repos/$owner/$repo/contents/${path.trimStart('/')}" + (ref?.let { "?ref=$it" } ?: "")
        return try {
            val (json, _) = call("GET", url, null)
            val o = json.jsonObject
            val encoded = o.str("content") ?: ""
            val decoded = String(Base64.getDecoder().decode(encoded.replace("\n", "").replace("\r", "")), StandardCharsets.UTF_8)
            GitHubContent(
                path = o.str("path").orEmpty(),
                sha = o.str("sha") ?: "",
                size = o.long("size") ?: 0,
                content = decoded,
                downloadUrl = o.str("download_url"),
            )
        } catch (e: GitHubException) {
            if (e.status == 404) null else throw e
        }
    }

    /** Create or update a file (`gh api -X PUT .../contents` equivalent). Pass [currentSha] to update an existing file. */
    suspend fun putContents(
        owner: String,
        repo: String,
        path: String,
        message: String,
        content: String,
        branch: String? = null,
        currentSha: String? = null,
    ): GitHubContent {
        val payload = buildJsonObject {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(content.toByteArray(StandardCharsets.UTF_8)))
            branch?.let { put("branch", it) }
            currentSha?.let { put("sha", it) }
        }
        val (json, _) = call("PUT", "/repos/$owner/$repo/contents/${path.trimStart('/')}", payload.toString())
        val o = (json as? JsonObject)?.get("content") as? JsonObject
        return GitHubContent(
            path = o?.str("path").orEmpty(),
            sha = o?.str("sha") ?: "",
            size = o?.long("size") ?: 0,
            content = null,
            downloadUrl = o?.str("download_url"),
        )
    }

    /** Create an issue (`gh issue create` equivalent). */
    suspend fun createIssue(
        owner: String,
        repo: String,
        title: String,
        body: String? = null,
        labels: List<String> = emptyList(),
    ): GitHubIssue {
        val payload = buildJsonObject {
            put("title", title)
            body?.let { put("body", it) }
            if (labels.isNotEmpty()) put("labels", labels.joinToString(","))
        }
        val (json, _) = call("POST", "/repos/$owner/$repo/issues", payload.toString())
        val o = json.jsonObject
        return GitHubIssue(
            number = o.str("number")?.toIntOrNull() ?: 0,
            title = o.str("title") ?: title,
            body = o.str("body"),
            htmlUrl = o.str("html_url").orEmpty(),
            state = o.str("state") ?: "open",
        )
    }

    /** List issues (`gh issue list` equivalent), [state] = open/closed/all. */
    suspend fun listIssues(owner: String, repo: String, state: String = "open"): List<GitHubIssue> {
        val (json, _) = call("GET", "/repos/$owner/$repo/issues?state=$state&per_page=50", null)
        return (json as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o ->
            GitHubIssue(
                number = o.str("number")?.toIntOrNull() ?: 0,
                title = o.str("title") ?: "",
                body = o.str("body"),
                htmlUrl = o.str("html_url").orEmpty(),
                state = o.str("state") ?: "open",
            )
        } }
    }

    /** Comment on an issue or pull request. */
    suspend fun createComment(owner: String, repo: String, issueNumber: Int, body: String): GitHubComment {
        val payload = buildJsonObject { put("body", body) }
        val (json, _) = call("POST", "/repos/$owner/$repo/issues/$issueNumber/comments", payload.toString())
        val o = json.jsonObject
        return GitHubComment(
            id = o.long("id") ?: 0,
            body = o.str("body").orEmpty(),
            htmlUrl = o.str("html_url").orEmpty(),
        )
    }

    /** List pull requests (`gh pr list` equivalent), [state] = open/closed/all. */
    suspend fun listPullRequests(owner: String, repo: String, state: String = "open"): List<PullRequest> {
        val (json, _) = call("GET", "/repos/$owner/$repo/pulls?state=$state&per_page=50", null)
        return (json as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o ->
            PullRequest(o.str("number")?.toIntOrNull() ?: 0, o.str("html_url").orEmpty(), o.str("title") ?: "")
        } }
    }

    /** Trigger a GitHub Actions workflow_dispatch (`gh workflow run` equivalent). */
    suspend fun dispatchWorkflow(
        owner: String,
        repo: String,
        workflowId: String,
        ref: String = "main",
        inputs: Map<String, String> = emptyMap(),
    ): Boolean {
        val payload = buildJsonObject {
            put("ref", ref)
            if (inputs.isNotEmpty()) {
                put("inputs", buildJsonObject { for ((k, v) in inputs) put(k, v) })
            }
        }
        return try {
            call("POST", "/repos/$owner/$repo/actions/workflows/$workflowId/dispatches", payload.toString())
            true
        } catch (e: GitHubException) {
            if (e.status == 204) true else throw e
        }
    }

    private fun repoOf(o: JsonObject): GitHubRepo? {
        val full = o.str("full_name") ?: return null
        return GitHubRepo(
            fullName = full,
            name = o.str("name") ?: full.substringAfter('/'),
            owner = (o["owner"] as? JsonObject)?.str("login") ?: full.substringBefore('/'),
            private = (o["private"] as? JsonPrimitive)?.booleanOrNull ?: false,
            description = o.str("description"),
            cloneUrl = o.str("clone_url") ?: "https://github.com/$full.git",
            defaultBranch = o.str("default_branch") ?: "main",
            pushedAt = o.str("pushed_at"),
        )
    }

    private suspend fun call(method: String, path: String, body: String?, withToken: String? = null): Pair<JsonElement, Map<String, String>> {
        val t = withToken ?: token() ?: throw GitHubException("Sign in to GitHub first.", 401)
        val request = Request.Builder()
            .url(apiBase.trimEnd('/') + path)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $t")
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
        return runInterruptible(Dispatchers.IO) {
            val response = try {
                http.newCall(request).execute()
            } catch (e: java.io.IOException) {
                throw GitHubException("Could not reach GitHub. Check the network and try again.", 0)
            }
            response.use { r ->
                val text = r.body.string()
                val json = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                if (!r.isSuccessful) throw GitHubException(explain(r.code, json, r.header("x-ratelimit-remaining")), r.code)
                (json ?: throw GitHubException("GitHub sent an unexpected answer.", r.code)) to
                    mapOf("x-oauth-scopes" to r.header("x-oauth-scopes").orEmpty())
            }
        }
    }

    companion object {
        /** GitHub's error answer as a message: 422 validation details (an existing pull request, no commits) first. */
        fun explain(code: Int, json: JsonElement?, rateRemaining: String? = null): String {
            val o = json as? JsonObject
            val message = o?.str("message").orEmpty()
            val details = (o?.get("errors") as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.let { it.str("message") ?: it.str("code") } }
            return when {
                code == 401 -> "GitHub rejected the token. Sign in again."
                code == 403 && rateRemaining == "0" -> "GitHub's rate limit is used up. Try again in a while."
                code == 403 -> "The token can't do this. It needs the repo scope (and access to this repository)."
                code == 404 -> "Repository not found, or the token can't see it."
                code == 422 && details.isNotEmpty() -> details.joinToString(". ").replaceFirstChar { it.uppercase() }
                message.isNotEmpty() -> message
                else -> "GitHub answered with HTTP $code."
            }
        }
    }
}

data class GitHubAccountInfo(val login: String, val name: String?, val id: Long)
