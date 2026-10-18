package com.bareflash.adb

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The RSA identity this host presents to a device. The device shows the key's
 * fingerprint in its "Allow USB debugging?" prompt, and remembers it if the
 * user ticks "Always allow".
 *
 * Android's adbd requires a 2048-bit key. The private key never leaves this
 * object except through [toPkcs8], which is for the [AdbKeyStore].
 */
public class AdbKeyPair private constructor(private val private: RSAPrivateCrtKey) {

    /**
     * Signs the device's 20-byte authentication [token]. adbd treats the token
     * as if it were a SHA-1 digest, so the signature is PKCS#1 v1.5 over the
     * SHA-1 DigestInfo prefix followed by the raw token, with no hashing here.
     */
    public fun signToken(token: ByteArray): ByteArray {
        require(token.size == AdbProtocol.AUTH_TOKEN_SIZE) { "Token must be ${AdbProtocol.AUTH_TOKEN_SIZE} bytes" }
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(private)
        sig.update(SHA1_DIGEST_INFO_PREFIX)
        sig.update(token)
        return sig.sign()
    }

    /**
     * The public key in the form adbd expects in an AUTH message: the
     * base64 of Android's 524-byte RSAPublicKey struct, a space, [name]
     * (normally `user@host`), and a terminating NUL.
     */
    public fun publicKeyMessage(name: String): ByteArray {
        require(name.none { it == '\u0000' || it == '\n' }) { "Invalid key name" }
        val b64 = Base64.getEncoder().encodeToString(androidPublicKey())
        return "$b64 $name".toByteArray(Charsets.UTF_8) + 0
    }

    /** MD5 of the key struct as upper-case hex pairs, the form recent Android versions display. */
    public fun fingerprint(): String =
        MessageDigest.getInstance("MD5").digest(androidPublicKey())
            .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    /** PKCS#8 DER encoding of the private key, for storage. Treat it as a secret. */
    public fun toPkcs8(): ByteArray = private.encoded

    /**
     * Android's `RSAPublicKey` struct: modulus length in words (64), `n0inv`
     * (-1/n[0] mod 2^32), the modulus and R^2 mod n as little-endian arrays
     * (R = 2^2048), and the public exponent.
     */
    internal fun androidPublicKey(): ByteArray {
        val n = private.modulus
        val e = private.publicExponent
        require(n.bitLength() in (MODULUS_BITS - 7)..MODULUS_BITS) { "Key is not 2048 bits" }
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).mod(two32).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(2 * MODULUS_BITS).mod(n)
        return ByteBuffer.allocate(STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MODULUS_BYTES / 4)
            .putInt(n0inv)
            .put(littleEndian(n, MODULUS_BYTES))
            .put(littleEndian(rr, MODULUS_BYTES))
            .putInt(e.toInt())
            .array()
    }

    public companion object {
        private const val MODULUS_BITS = 2048
        private const val MODULUS_BYTES = MODULUS_BITS / 8
        private const val STRUCT_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

        /** ASN.1 DigestInfo header for SHA-1: SEQUENCE { SEQUENCE { OID sha1, NULL }, OCTET STRING (20) }. */
        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        public fun generate(): AdbKeyPair {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(MODULUS_BITS)
            return AdbKeyPair(gen.generateKeyPair().private as RSAPrivateCrtKey)
        }

        public fun fromPkcs8(bytes: ByteArray): AdbKeyPair {
            val key = try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
            } catch (e: java.security.GeneralSecurityException) {
                throw IllegalArgumentException("Not a valid RSA private key", e)
            }
            require(key is RSAPrivateCrtKey) { "Key is not an RSA CRT key" }
            require(key.modulus.bitLength() in (MODULUS_BITS - 7)..MODULUS_BITS) { "Key is not 2048 bits" }
            return AdbKeyPair(key)
        }

        private fun littleEndian(value: BigInteger, size: Int): ByteArray {
            val be = value.toByteArray() // may carry a leading sign byte
            val out = ByteArray(size)
            var i = 0
            var j = be.size - 1
            while (j >= 0 && i < size) out[i++] = be[j--]
            while (j >= 0) require(be[j--].toInt() == 0) { "Value does not fit in $size bytes" }
            return out
        }
    }
}

/** Where the host's private key lives between runs. */
public interface AdbKeyStore {
    /** The stored PKCS#8 key, or null if none exists yet. */
    public fun load(): ByteArray?

    /** Replaces the stored key. Must not leave a half-written key behind. */
    public fun save(pkcs8: ByteArray)
}

/** Returns the stored key, or creates, stores and returns a new one. */
public fun AdbKeyStore.loadOrCreate(): AdbKeyPair {
    load()?.let { return AdbKeyPair.fromPkcs8(it) }
    val fresh = AdbKeyPair.generate()
    save(fresh.toPkcs8())
    return fresh
}

/** A key stored in a single file, written atomically and readable only by its owner where the platform allows. */
public class FileAdbKeyStore(private val file: File) : AdbKeyStore {
    override fun load(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun save(pkcs8: ByteArray) {
        val dir = file.absoluteFile.parentFile ?: throw java.io.IOException("No parent directory")
        dir.mkdirs()
        val tmp = File.createTempFile("adbkey", ".tmp", dir)
        try {
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            tmp.setWritable(false, false); tmp.setWritable(true, true)
            tmp.writeBytes(pkcs8)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }
}
