package opensec.pibolt.app.git.github

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * Environment that lets every git the engine runs (the app's own and Pi's bash commands) use the GitHub token for
 * `https://github.com` only, with the token in no environment and no file: a URL-scoped credential helper set through
 * `GIT_CONFIG_COUNT` (git 2.31+) that asks the app for it over [GitCredentialBroker]'s socket with the pack's curl.
 * The helper is a shell function (git runs `!` helpers through `sh`, which lives in nativeLibraryDir; scripts written
 * to the data dir could not be executed). The empty helper first clears any other helper for github.com, so a stale
 * stored password is never tried.
 */
object GitCredentials {
    const val SOCKET_VAR = "PIBOLT_GIT_CREDENTIALS"
    const val HELPER = "!f() { test \"\$1\" = get || exit 0; test -n \"\$$SOCKET_VAR\" || exit 0; " +
        "curl -s --max-time 10 --unix-socket \"\$$SOCKET_VAR\" http://pibolt/github; }; f"

    /** The variables for [socket] (the broker's), empty without one (signed out). */
    fun env(socket: String?): Map<String, String> {
        if (socket.isNullOrEmpty()) return emptyMap()
        return mapOf(
            "GIT_CONFIG_COUNT" to "2",
            "GIT_CONFIG_KEY_0" to "credential.https://github.com.helper",
            "GIT_CONFIG_VALUE_0" to "",
            "GIT_CONFIG_KEY_1" to "credential.https://github.com.helper",
            "GIT_CONFIG_VALUE_1" to HELPER,
            SOCKET_VAR to socket,
        )
    }
}

/** Sign-in state for the UI. */
sealed interface GitHubState {
    data object SignedOut : GitHubState

    data class SignedIn(val account: GitHubAccount) : GitHubState
}

/**
 * The app's GitHub connection: the sealed token ([TokenStore]), the account, the device flow and the REST client.
 * [clientId] is the OAuth App's (BuildConfig.GITHUB_CLIENT_ID); empty means device flow is off and only a personal
 * access token can be used. [onCredentialsChanged] lets the engine restart its idle process with the new environment.
 */
class GitHub(
    private val store: TokenStore,
    private val http: OkHttpClient,
    val clientId: String,
    private val onCredentialsChanged: () -> Unit = {},
    socketBroker: ((() -> Pair<String, String>?) -> GitCredentialBroker)? = null,
    private val webBase: String = "https://github.com",
    apiBase: String = "https://api.github.com",
) {
    private val _state = MutableStateFlow<GitHubState>(store.account()?.let { GitHubState.SignedIn(it) } ?: GitHubState.SignedOut)
    val state: StateFlow<GitHubState> = _state.asStateFlow()

    val api = GitHubApi(http, store::token, apiBase)

    private val broker: GitCredentialBroker? = socketBroker?.invoke {
        store.token()?.takeIf { it.isNotEmpty() }?.let { (account?.login?.takeIf(String::isNotBlank) ?: "x-access-token") to it }
    }

    val deviceFlowAvailable: Boolean get() = clientId.isNotBlank()

    val account: GitHubAccount? get() = (_state.value as? GitHubState.SignedIn)?.account

    fun deviceFlow(): DeviceFlow = DeviceFlow(http, clientId, webBase)

    /** Checks [token] against `/user`, then stores it sealed. The token is not kept anywhere else. */
    suspend fun signIn(token: String, kind: TokenKind): GitHubAccount {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) throw GitHubException("Paste a token first.", 0)
        val (info, scopes) = api.user(withToken = trimmed)
        val account = GitHubAccount(info.login, info.name, info.id, kind, scopes)
        store.save(trimmed, account)
        _state.value = GitHubState.SignedIn(account)
        onCredentialsChanged()
        return account
    }

    fun signOut() {
        store.clear()
        _state.value = GitHubState.SignedOut
        onCredentialsChanged()
    }

    /** The credential environment for git processes (empty when signed out). */
    fun gitEnvironment(): Map<String, String> {
        if (store.token().isNullOrEmpty()) return emptyMap()
        return GitCredentials.env(broker?.ensureListening()?.path)
    }

    override fun toString(): String = "GitHub(state=${_state.value})"

    companion object {
        /** What a personal access token needs: repo (clone, pull, push private repositories), workflow (push CI files). */
        val SCOPES = listOf("repo", "workflow")
    }
}
