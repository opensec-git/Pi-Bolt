package opensec.pibolt.protocol.noise

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Noise_IK_25519_AESGCM_SHA256, as in the Noise Protocol Framework (revision 34).
 *
 * X25519 comes from BouncyCastle's lightweight API (the platform has it only from Android 12); AES-GCM, SHA-256 and
 * HMAC come from javax.crypto, which every supported Android version has.
 */
object Noise {
    const val PROTOCOL_NAME = "Noise_IK_25519_AESGCM_SHA256"
    const val DH_LEN = 32
    const val HASH_LEN = 32
    const val TAG_LEN = 16
    const val MAX_MESSAGE_LEN = 65535
}

class NoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)

class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    init {
        require(privateKey.size == Noise.DH_LEN && publicKey.size == Noise.DH_LEN)
    }

    companion object {
        private val random = SecureRandom()

        fun generate(): KeyPair = fromPrivate(X25519PrivateKeyParameters(random).encoded)

        fun fromPrivate(privateKey: ByteArray): KeyPair {
            val params = X25519PrivateKeyParameters(privateKey, 0)
            return KeyPair(privateKey.copyOf(), params.generatePublicKey().encoded)
        }
    }
}

internal object Crypto {
    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val out = ByteArray(agreement.agreementSize)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), out, 0)
        } catch (e: IllegalStateException) {
            throw NoiseException("X25519 agreement failed", e)
        }
        return out
    }

    fun hash(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    /** HKDF as defined by Noise: two or three outputs of HASHLEN bytes. */
    fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
        val tempKey = hmac(chainingKey, ikm)
        val o1 = hmac(tempKey, byteArrayOf(1))
        val o2 = hmac(tempKey, o1, byteArrayOf(2))
        if (outputs == 2) return listOf(o1, o2)
        val o3 = hmac(tempKey, o2, byteArrayOf(3))
        return listOf(o1, o2, o3)
    }
}

/** A Noise CipherState over AES-256-GCM. The nonce is 4 zero bytes followed by the 64-bit counter, big-endian. */
class CipherState internal constructor() {
    private var key: SecretKeySpec? = null
    private var nonce: Long = 0
    private val cipher: Cipher by lazy { Cipher.getInstance("AES/GCM/NoPadding") }

    internal fun initializeKey(k: ByteArray?) {
        key = k?.let { SecretKeySpec(it, "AES") }
        nonce = 0
    }

    val hasKey: Boolean get() = key != null

    private fun nonceBytes(n: Long): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 8) out[11 - i] = (n ushr (8 * i)).toByte()
        return out
    }

    private fun nextNonce(): Long {
        if (nonce == -1L) throw NoiseException("Nonce exhausted")
        return nonce++
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext.copyOf()
        val n = nextNonce()
        synchronized(cipher) {
            cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(Noise.TAG_LEN * 8, nonceBytes(n)))
            if (ad.isNotEmpty()) cipher.updateAAD(ad)
            return cipher.doFinal(plaintext)
        }
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext.copyOf()
        if (ciphertext.size < Noise.TAG_LEN) throw NoiseException("Ciphertext too short")
        // The counter only advances on success, as the spec requires.
        if (nonce == -1L) throw NoiseException("Nonce exhausted")
        val plaintext = synchronized(cipher) {
            try {
                cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(Noise.TAG_LEN * 8, nonceBytes(nonce)))
                if (ad.isNotEmpty()) cipher.updateAAD(ad)
                cipher.doFinal(ciphertext)
            } catch (e: AEADBadTagException) {
                throw NoiseException("Decryption failed", e)
            }
        }
        nonce++
        return plaintext
    }

    fun encrypt(plaintext: ByteArray): ByteArray = encryptWithAd(EMPTY, plaintext)

    fun decrypt(ciphertext: ByteArray): ByteArray = decryptWithAd(EMPTY, ciphertext)

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

internal class SymmetricState(protocolName: String) {
    val cipher = CipherState()
    var ck: ByteArray
    var h: ByteArray

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= Noise.HASH_LEN) name.copyOf(Noise.HASH_LEN) else Crypto.hash(name)
        ck = h.copyOf()
        cipher.initializeKey(null)
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = Crypto.hkdf(ck, ikm, 2)
        ck = newCk
        cipher.initializeKey(tempK)
    }

    fun mixHash(data: ByteArray) {
        h = Crypto.hash(h, data)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encryptWithAd(h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = Crypto.hkdf(ck, ByteArray(0), 2)
        val c1 = CipherState().apply { initializeKey(k1) }
        val c2 = CipherState().apply { initializeKey(k2) }
        return c1 to c2
    }
}

/** The two transport ciphers after a completed handshake, seen from one side. */
class TransportCiphers(val send: CipherState, val receive: CipherState, val handshakeHash: ByteArray)

/**
 * The IK handshake:
 * ```
 * <- s
 * ...
 * -> e, es, s, ss
 * <- e, ee, se
 * ```
 * [ephemeral] is injectable only for test vectors.
 */
class IkHandshake private constructor(
    private val initiator: Boolean,
    private val localStatic: KeyPair,
    remoteStatic: ByteArray?,
    prologue: ByteArray,
    private val ephemeral: KeyPair?,
    protocolName: String,
) {
    private val ss = SymmetricState(protocolName)
    private var localEphemeral: KeyPair? = null
    private var rs: ByteArray? = remoteStatic
    private var re: ByteArray? = null
    private var step = 0

    /** The peer's static public key: known up front for the initiator, learned from message 1 by the responder. */
    val remoteStaticKey: ByteArray? get() = rs?.copyOf()

    init {
        ss.mixHash(prologue)
        if (initiator) ss.mixHash(requireNotNull(remoteStatic) { "IK initiator needs the responder's static key" })
        else ss.mixHash(localStatic.publicKey)
    }

    /** Initiator: build message 1. */
    fun writeMessage1(payload: ByteArray): ByteArray {
        check(initiator && step == 0)
        val e = ephemeral ?: KeyPair.generate()
        localEphemeral = e
        val out = ArrayList<ByteArray>()
        out += e.publicKey
        ss.mixHash(e.publicKey)
        ss.mixKey(Crypto.dh(e.privateKey, rs!!))
        out += ss.encryptAndHash(localStatic.publicKey)
        ss.mixKey(Crypto.dh(localStatic.privateKey, rs!!))
        out += ss.encryptAndHash(payload)
        step = 1
        return concat(out)
    }

    /** Responder: read message 1 and return its payload. [remoteStaticKey] is set afterwards. */
    fun readMessage1(message: ByteArray): ByteArray {
        check(!initiator && step == 0)
        val sLen = Noise.DH_LEN + Noise.TAG_LEN
        if (message.size < Noise.DH_LEN + sLen + Noise.TAG_LEN) throw NoiseException("Handshake message 1 too short")
        val reKey = message.copyOfRange(0, Noise.DH_LEN)
        re = reKey
        ss.mixHash(reKey)
        ss.mixKey(Crypto.dh(localStatic.privateKey, reKey))
        val remote = ss.decryptAndHash(message.copyOfRange(Noise.DH_LEN, Noise.DH_LEN + sLen))
        rs = remote
        ss.mixKey(Crypto.dh(localStatic.privateKey, remote))
        val payload = ss.decryptAndHash(message.copyOfRange(Noise.DH_LEN + sLen, message.size))
        step = 1
        return payload
    }

    /** Responder: build message 2. */
    fun writeMessage2(payload: ByteArray): ByteArray {
        check(!initiator && step == 1)
        val e = ephemeral ?: KeyPair.generate()
        localEphemeral = e
        val out = ArrayList<ByteArray>()
        out += e.publicKey
        ss.mixHash(e.publicKey)
        ss.mixKey(Crypto.dh(e.privateKey, re!!))
        ss.mixKey(Crypto.dh(e.privateKey, rs!!))
        out += ss.encryptAndHash(payload)
        step = 2
        return concat(out)
    }

    /** Initiator: read message 2 and return its payload. */
    fun readMessage2(message: ByteArray): ByteArray {
        check(initiator && step == 1)
        if (message.size < Noise.DH_LEN + Noise.TAG_LEN) throw NoiseException("Handshake message 2 too short")
        val reKey = message.copyOfRange(0, Noise.DH_LEN)
        re = reKey
        ss.mixHash(reKey)
        val e = localEphemeral!!
        ss.mixKey(Crypto.dh(e.privateKey, reKey))
        ss.mixKey(Crypto.dh(localStatic.privateKey, reKey))
        val payload = ss.decryptAndHash(message.copyOfRange(Noise.DH_LEN, message.size))
        step = 2
        return payload
    }

    /** After message 2: the transport ciphers for this side. */
    fun split(): TransportCiphers {
        check(step == 2) { "Handshake not complete" }
        val (c1, c2) = ss.split()
        return if (initiator) TransportCiphers(c1, c2, ss.h.copyOf()) else TransportCiphers(c2, c1, ss.h.copyOf())
    }

    companion object {
        fun initiator(
            localStatic: KeyPair,
            remoteStatic: ByteArray,
            prologue: ByteArray,
            ephemeral: KeyPair? = null,
            protocolName: String = Noise.PROTOCOL_NAME,
        ) = IkHandshake(true, localStatic, remoteStatic.copyOf(), prologue, ephemeral, protocolName)

        fun responder(
            localStatic: KeyPair,
            prologue: ByteArray,
            ephemeral: KeyPair? = null,
            protocolName: String = Noise.PROTOCOL_NAME,
        ) = IkHandshake(false, localStatic, null, prologue, ephemeral, protocolName)

        private fun concat(parts: List<ByteArray>): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var offset = 0
            for (p in parts) {
                p.copyInto(out, offset)
                offset += p.size
            }
            return out
        }
    }
}
