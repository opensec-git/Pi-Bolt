package opensec.pibolt.app.git.github

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** A step of GitHub's device flow, as the sign-in sheet shows it. */
sealed interface DeviceFlowState {
    data object Requesting : DeviceFlowState

    /** Show [userCode] and send the user to [verificationUri]; polling runs meanwhile. */
    data class ShowCode(val userCode: String, val verificationUri: String, val expiresInSec: Long) : DeviceFlowState

    /** Authorised. [token] goes straight to the token store; never shown or logged ([toString] hides it). */
    class Authorized(val token: String, val scopes: List<String>) : DeviceFlowState {
        override fun toString(): String = "Authorized(scopes=$scopes)"
    }

    data class Failed(val message: String, val canRetry: Boolean) : DeviceFlowState
}

/**
 * GitHub's OAuth device flow (docs.github.com, "Authorizing OAuth apps", device flow): request a device and user code,
 * show the code, poll the token endpoint every `interval` seconds until the user authorises it in a browser.
 * `authorization_pending` keeps polling, `slow_down` adds 5 seconds to the interval, `expired_token`,
 * `access_denied` and configuration errors end the flow. [sleep] is injectable so tests run without waiting.
 */
class DeviceFlow(
    private val http: OkHttpClient,
    private val clientId: String,
    private val baseUrl: String = "https://github.com",
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun run(scopes: List<String>): Flow<DeviceFlowState> = flow {
        emit(DeviceFlowState.Requesting)
        val code = try {
            post("/login/device/code", mapOf("client_id" to clientId, "scope" to scopes.joinToString(" ")))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emit(DeviceFlowState.Failed("Could not reach GitHub. Check the network and try again.", canRetry = true))
            return@flow
        }
        code.str("error")?.let {
            emit(DeviceFlowState.Failed(explain(it, code.str("error_description")), canRetry = false))
            return@flow
        }
        val deviceCode = code.str("device_code")
        val userCode = code.str("user_code")
        if (deviceCode == null || userCode == null) {
            emit(DeviceFlowState.Failed("GitHub sent an unexpected answer.", canRetry = true))
            return@flow
        }
        val expiresIn = code.long("expires_in") ?: 900
        var interval = (code.long("interval") ?: 5).coerceAtLeast(1)
        emit(DeviceFlowState.ShowCode(userCode, code.str("verification_uri") ?: "https://github.com/login/device", expiresIn))
        var waited = 0L
        while (waited < expiresIn) {
            sleep(interval * 1000)
            waited += interval
            val answer = try {
                post(
                    "/login/oauth/access_token",
                    mapOf("client_id" to clientId, "device_code" to deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A dropped request while the user is in the browser: try again on the next tick.
                continue
            }
            val token = answer.str("access_token")
            if (token != null) {
                emit(DeviceFlowState.Authorized(token, answer.str("scope").orEmpty().split(',', ' ').filter { it.isNotBlank() }))
                return@flow
            }
            when (val error = answer.str("error")) {
                "authorization_pending", null -> Unit
                "slow_down" -> interval = (answer.long("interval") ?: (interval + 5)).coerceAtLeast(interval + 5)
                else -> {
                    emit(DeviceFlowState.Failed(explain(error, answer.str("error_description")), canRetry = error == "expired_token" || error == "access_denied"))
                    return@flow
                }
            }
        }
        emit(DeviceFlowState.Failed("The code expired. Start again for a new one.", canRetry = true))
    }

    private suspend fun post(path: String, form: Map<String, String>): JsonObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).header("Accept", "application/json").post(body).build()
        return runInterruptible(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IllegalStateException("HTTP ${response.code}") }
            }
        }
    }

    companion object {
        fun explain(error: String, description: String?): String = when (error) {
            "expired_token" -> "The code expired. Start again for a new one."
            "access_denied" -> "Sign-in was cancelled on GitHub."
            "device_flow_disabled" -> "Device flow is not enabled for this app's GitHub OAuth App."
            "incorrect_client_credentials", "unauthorized_client" -> "This build's GitHub client id is not valid."
            "unsupported_grant_type", "incorrect_device_code" -> "GitHub rejected the sign-in request."
            else -> description?.takeIf { it.isNotBlank() } ?: "GitHub sign-in failed ($error)."
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
