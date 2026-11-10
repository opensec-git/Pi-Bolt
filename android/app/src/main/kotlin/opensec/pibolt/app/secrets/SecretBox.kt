package opensec.pibolt.app.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM sealing for project secrets. The blob is `version (1) | iv length (1) | iv | ciphertext+tag`; the
 * associated data binds a blob to its project, so a file copied over another project's does not open.
 * The cipher picks the IV (Android Keystore requires that), so the same code runs with a Keystore key on the phone and
 * a plain JCE key in unit tests.
 */
class SecretBox(private val key: () -> SecretKey) {
    fun seal(plain: ByteArray, associated: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associated)
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return ByteBuffer.allocate(2 + iv.size + sealed.size).put(VERSION).put(iv.size.toByte()).put(iv).put(sealed).array()
    }

    /** The plain bytes; throws on a foreign, damaged or tampered blob (GCM checks the tag). */
    fun open(blob: ByteArray, associated: ByteArray): ByteArray {
        require(blob.size > 2 && blob[0] == VERSION) { "not a secrets file" }
        val ivLength = blob[1].toInt() and 0xFF
        require(ivLength in 12..16 && blob.size > 2 + ivLength + TAG_BITS / 8) { "damaged secrets file" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 2, ivLength))
        cipher.updateAAD(associated)
        return cipher.doFinal(blob, 2 + ivLength, blob.size - 2 - ivLength)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val VERSION: Byte = 1
        private const val KEYSTORE = "AndroidKeyStore"

        /** The app's secrets key in the Android Keystore (created on first use; it never leaves the keystore). */
        fun keystoreKey(alias: String = "pibolt.project-secrets"): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
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
