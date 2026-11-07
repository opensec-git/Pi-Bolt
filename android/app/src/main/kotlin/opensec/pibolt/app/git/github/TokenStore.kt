package opensec.pibolt.app.git.github

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets. [KeystoreBox] on the phone; tests pass a box with a key in memory. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/** AES-256-GCM with [key]: 12-byte random IV, then ciphertext and tag. */
open class AesGcmBox(private val key: () -> SecretKey) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV) { "sealed value too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV))
        return cipher.doFinal(sealed, IV, sealed.size - IV)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV = 12
    }
}

/**
 * The key lives in the Android Keystore (never leaves it; not in backups): a value sealed here can only be opened by
 * this app on this device.
 */
class KeystoreBox(private val alias: String) : AesGcmBox({ keystoreKey(alias) }) {
    private companion object {
        fun keystoreKey(alias: String): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}

/** Where the sealed token is kept: SharedPreferences on the phone, a map in tests. */
interface KeyValue {
    fun get(key: String): String?

    fun put(key: String, value: String?)
}

class PrefsKeyValue(private val prefs: SharedPreferences) : KeyValue {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
}

/** How the token was obtained. */
enum class TokenKind { Device, Personal }

/** The signed-in GitHub account as the UI may show it: never the token. */
data class GitHubAccount(val login: String, val name: String?, val id: Long, val kind: TokenKind, val scopes: List<String>) {
    /** GitHub's private commit email for this account. */
    val noreplyEmail: String get() = "$id+$login@users.noreply.github.com"
}

/**
 * The GitHub token, sealed by [box] before it is written to [kv] (base64 of IV + ciphertext). The plain token is
 * only returned by [token] for the HTTP and git calls that need it; [toString] and the account never contain it, and
 * nothing here logs. A value that no longer opens (the Keystore key was reset) reads as signed out.
 */
class TokenStore(private val kv: KeyValue, private val box: SecretBox) {
    fun save(token: String, account: GitHubAccount) {
        val sealed = box.seal(token.toByteArray(Charsets.UTF_8))
        kv.put(KEY_TOKEN, Base64.getEncoder().encodeToString(sealed))
        kv.put(KEY_LOGIN, account.login)
        kv.put(KEY_NAME, account.name)
        kv.put(KEY_ID, account.id.toString())
        kv.put(KEY_KIND, account.kind.name)
        kv.put(KEY_SCOPES, account.scopes.joinToString(","))
    }

    fun token(): String? {
        val stored = kv.get(KEY_TOKEN) ?: return null
        return runCatching { String(box.open(Base64.getDecoder().decode(stored)), Charsets.UTF_8) }.getOrNull()
    }

    fun account(): GitHubAccount? {
        if (kv.get(KEY_TOKEN) == null) return null
        val login = kv.get(KEY_LOGIN) ?: return null
        return GitHubAccount(
            login = login,
            name = kv.get(KEY_NAME),
            id = kv.get(KEY_ID)?.toLongOrNull() ?: 0,
            kind = runCatching { TokenKind.valueOf(kv.get(KEY_KIND).orEmpty()) }.getOrDefault(TokenKind.Personal),
            scopes = kv.get(KEY_SCOPES).orEmpty().split(',').filter { it.isNotBlank() },
        )
    }

    fun clear() {
        for (k in listOf(KEY_TOKEN, KEY_LOGIN, KEY_NAME, KEY_ID, KEY_KIND, KEY_SCOPES)) kv.put(k, null)
    }

    override fun toString(): String = "TokenStore(signedIn=${kv.get(KEY_TOKEN) != null})"

    private companion object {
        const val KEY_TOKEN = "token.sealed"
        const val KEY_LOGIN = "login"
        const val KEY_NAME = "name"
        const val KEY_ID = "id"
        const val KEY_KIND = "kind"
        const val KEY_SCOPES = "scopes"
    }
}

/** Removes anything shaped like a GitHub token (and [secrets] themselves) from text bound for a log or the screen. */
object Redact {
    private val tokenShape = Regex("""\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b""")

    fun text(text: String, vararg secrets: String?): String {
        var out = tokenShape.replace(text, "[token]")
        for (s in secrets) if (!s.isNullOrEmpty() && s.length >= 8) out = out.replace(s, "[token]")
        return out
    }
}
