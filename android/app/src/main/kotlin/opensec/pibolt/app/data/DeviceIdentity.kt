package opensec.pibolt.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import opensec.pibolt.protocol.noise.KeyPair
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This install's X25519 static key pair. The private key is stored encrypted with an AES-256-GCM key that lives in the
 * Android Keystore and never leaves it, so a copy of the app's files does not give away the key.
 *
 * File layout: 1 byte IV length, IV, ciphertext (with tag).
 */
class DeviceIdentity(context: Context) {
    private val file = File(context.noBackupFilesDir, "device-key.bin")

    @Volatile
    private var cached: KeyPair? = null

    @Synchronized
    fun keyPair(): KeyPair {
        cached?.let { return it }
        val loaded = runCatching { load() }.getOrNull()
        val pair = loaded ?: KeyPair.generate().also { store(it) }
        cached = pair
        return pair
    }

    /** Make a new key: every computer has to pair again. */
    @Synchronized
    fun reset() {
        cached = null
        file.delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun load(): KeyPair? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val ivLen = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val ciphertext = bytes.copyOfRange(1 + ivLen, bytes.size)
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return KeyPair.fromPrivate(cipher.doFinal(ciphertext))
    }

    private fun store(pair: KeyPair) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(pair.privateKey)
        val iv = cipher.iv
        val out = ByteArray(1 + iv.size + ciphertext.size)
        out[0] = iv.size.toByte()
        iv.copyInto(out, 1)
        ciphertext.copyInto(out, 1 + iv.size)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            file.writeBytes(out)
            tmp.delete()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pibolt-device-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
