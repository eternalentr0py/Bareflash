package com.bareflash.adb

import com.bareflash.fakes.FakeAdbdCore
import com.bareflash.fakes.TestKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher

class AdbKeyPairTest {

    // Reference values produced outside this code base: the key encoding by the
    // adb-shell Python library (encode_pubkey), the signature by OpenSSL via
    // the `cryptography` package, treating the token as a pre-hashed SHA-1 digest.
    private val referenceKey = AdbKeyPair.fromPkcs8(Base64.getDecoder().decode(PKCS8_B64))
    private val referenceToken = ByteArray(20) { (it + 1).toByte() }

    @Test fun publicKeyStructMatchesTheReferenceEncoder() {
        assertArrayEquals(Base64.getDecoder().decode(ANDROID_BLOB_B64), referenceKey.androidPublicKey())
    }

    @Test fun signatureMatchesTheReference() {
        assertArrayEquals(Base64.getDecoder().decode(SIG_B64), referenceKey.signToken(referenceToken))
    }

    @Test fun publicKeyMessageHasBase64SpaceNameAndNul() {
        val msg = referenceKey.publicKeyMessage("me@host")
        assertEquals(0, msg.last().toInt())
        val text = String(msg, 0, msg.size - 1)
        assertEquals("me@host", text.substringAfter(' '))
        assertArrayEquals(Base64.getDecoder().decode(ANDROID_BLOB_B64), Base64.getDecoder().decode(text.substringBefore(' ')))
    }

    @Test fun keyNameCannotInjectControlCharacters() {
        try { referenceKey.publicKeyMessage("a\u0000b"); fail() } catch (_: IllegalArgumentException) { assertTrue(true) }
        try { referenceKey.publicKeyMessage("a\nb"); fail() } catch (_: IllegalArgumentException) { assertTrue(true) }
    }

    @Test fun generatedKeyStructSatisfiesItsOwnMath() {
        val raw = TestKeys.pair.androidPublicKey()
        assertEquals(524, raw.size)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.getInt(0))
        val n = BigInteger(1, raw.copyOfRange(8, 264).reversedArray())
        val rr = BigInteger(1, raw.copyOfRange(264, 520).reversedArray())
        assertEquals(BigInteger.valueOf(65537), BigInteger.valueOf(b.getInt(520).toLong()))
        // n0inv * n[0] == -1 (mod 2^32)
        val n0inv = BigInteger.valueOf(b.getInt(4).toLong() and 0xFFFFFFFFL)
        val two32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(two32.subtract(BigInteger.ONE), n0inv.multiply(n.mod(two32)).mod(two32))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), rr)
    }

    @Test fun signatureOfAGeneratedKeyVerifiesAsPkcs1OverTheDigestInfo() {
        val token = ByteArray(20) { (it * 7).toByte() }
        val sig = TestKeys.pair.signToken(token)
        val pub = FakeAdbdCore.parseAndroidKey(TestKeys.pair.publicKeyMessage("x@y"))
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.DECRYPT_MODE, pub)
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        assertArrayEquals(prefix + token, c.doFinal(sig))
    }

    @Test fun tokenMustBeTwentyBytes() {
        try { TestKeys.pair.signToken(ByteArray(19)); fail() } catch (_: IllegalArgumentException) { assertTrue(true) }
    }

    @Test fun fingerprintIsSixteenHexPairs() {
        assertTrue(Regex("([0-9A-F]{2}:){15}[0-9A-F]{2}").matches(TestKeys.pair.fingerprint()))
    }

    @Test fun pkcs8RoundTripKeepsTheIdentity() {
        val again = AdbKeyPair.fromPkcs8(TestKeys.pair.toPkcs8())
        assertEquals(TestKeys.pair.fingerprint(), again.fingerprint())
    }

    @Test fun badKeysAreRejected() {
        try { AdbKeyPair.fromPkcs8(ByteArray(10)); fail() } catch (_: IllegalArgumentException) { assertTrue(true) }
        val small = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private.encoded
        try { AdbKeyPair.fromPkcs8(small); fail() } catch (_: IllegalArgumentException) { assertTrue(true) }
    }

    @Test fun fileStoreCreatesOnceAndReloadsTheSameKey() {
        val dir = java.nio.file.Files.createTempDirectory("adbkeys").toFile()
        try {
            val store = FileAdbKeyStore(java.io.File(dir, "sub/adbkey"))
            assertNull(store.load())
            val first = store.loadOrCreate()
            val second = FileAdbKeyStore(java.io.File(dir, "sub/adbkey")).loadOrCreate()
            assertEquals(first.fingerprint(), second.fingerprint())
            assertEquals(1, java.io.File(dir, "sub").listFiles()!!.size) // no temp file left behind
        } finally {
            dir.deleteRecursively()
        }
    }
}

private const val PKCS8_B64 =
    "MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQC1a8kNrpN5nZD4B7/iw39IVmTn5u/pxWQK3+n1oH3B9I8fWh3/" +
        "H4U4urWQrqjSstnPYE4V3Xwyrc2ndZjI4PxXGe7rnOYb3jZEbTzEyMjJZwo5J5n2T2nYm9oD884hjxoeoLvxGgfq4zl8T0iX03/A" +
        "lxqg5JL13ulqzAGVqE+SRMYNFJWn1XqOFNKJBA1ut7VhIua64P8TlTb3NeKdGrSwF1exHgQDeT5Us/Nj9VvNXui1Iqu/dM4BITeq" +
        "/qWgAILeReDSlcRjSH8//RbBtFYSzmjlCjjzxeTkQkqZSwQboa89HqRQ2Wjm/9gO1lFPBk0nZdYZqFklwmcPdFmoi64fAgMBAAEC" +
        "ggEAGd7UbUyLQCjtbskgHlSVdnnHauBeLt5UjU5v3PR1am6W6DggFeznQPczA/48GcuiTrWjfO+4EbwCGxKkZmTa5wrOLLATij7O" +
        "S0SuiTs+kqXMaekRRfP+GoLi/HRMXTG8Xki6dD+riF29qVHe/rOMwKhrhi0CHeibLX0I7s/xBODmw4wnR3TzWi1mvBrQZPUo/PdH" +
        "IUeoXHnaRR39+rCbgkXFoMEmVmwDCofMyDRQXMpMeJ1otv74qsb36pu/0uqaM11K4mJratUr8/lPJfX6pIvanPCqZ3A35NK3NvrW" +
        "M6UvMfJmeFJUtpBPFVN2ayG335MeTimCmgMqA7r2YSn+gQKBgQD14Sx70uOhN6FSxAQOfGz9D893URGU9fRVQYOwPzG1e/t9CKdu" +
        "qe00DGgthSILW7fEatWqqBib9wy9gKNggqWfcO7+ZqTa8T/Bt5nrtesbqFGxlIF4qwOFJ+R0ni/agD+l9OxMuDcPyshShwlOeoBr" +
        "uQYbENSJyn+uPn7clJMGnwKBgQC842noH9uf+Tnvdrk5+Hst8G4ZevbGMXR8HEZxvDitYmUh/QRy0bI9kwqxT9wF+T4/UM9UmdrJ" +
        "gFvRDbL01dkVh5F4kh5OqzpeT6nMG9IyTpZvAMkydG8QLA7VdH8Eqw4G1yhKkJmN1KcFLXG3t2MM6Md4fVIR9ix26XLj3cOogQKB" +
        "gQDSiMBCNk1a2ft0lwqyLLBV75fBntTjK8bOyY4s969kqoiogyR6iV3mV0lM0NZZy2sK8D+MJOsNgPiTuvYpyTRv1Y+One1C25ew" +
        "E7nkLpzlzbZFy9cgr64P8hyYDmsDORVG0PUUrBfZmdT1dVLus84Kbx9fRYX3hkDVvgV6cm2CGQKBgQCPDCrIgFfzbutRTrg4DtY6" +
        "lDrNUTJSJmHxG/i9aa4CoOG2O8eCrQwvVYxje/dFDtz1Vl2t1Fa9A1iFmbpNcSZWB7VDemU5brNleS8+RFOLhbinmBDHBSaXOMaD" +
        "IRaQknqs84Sv85JWtxVF9oSIfXml7SQjO8VLlQNXirRXnTrwAQKBgQCqw3C2YKpt32RKD+vW+NnQsYjADql3EpDSio3bvGrnCqcU" +
        "aJft35tLkLGfh0vX1SmRm/+N6pF2LgGN62WCTHq0aFg8MKkgteg4pIL9Nxx4x5ngA6ohQgvJPI0CjWucuUbSouzyCAv2Zw4VnwIm" +
        "oNKdQlYxXxgn6/0GgwF50DjT8g=="

private const val ANDROID_BLOB_B64 =
    "QAAAACGyczEfrouoWXQPZ8IlWagZ1mUnTQZPUdYO2P/maNlQpB49r6EbBEuZSkLk5MXzOArlaM4SVrTBFv0/f0hjxJXS4EXeggCg" +
        "pf6qNyEBznS/qyK16F7NW/Vj87NUPnkDBB6xVxewtBqd4jX3NpUT/+C65iJhtbduDQSJ0hSOetWnlRQNxkSST6iVAcxq6d71kuSg" +
        "GpfAf9OXSE98OePqBxrxu6AeGo8hzvMD2pvYaU/2mSc5CmfJyMjEPG1ENt4b5pzr7hlX/ODImHWnza0yfN0VTmDP2bLSqK6Qtbo4" +
        "hR//HVofj/TBfaD16d8KZMXp7+bnZFZIf8Pivwf4kJ15k64NyWu12FqkRTVVT7XH9NbzMBrJxRD7fBPfNq5z0lJCis9VILzutCan" +
        "HsP1tsQz2ym4k0KAkj5mWwNmvI9MRqFdojGMDV9X+1pJYekBjPYfgRSHeVPOVAIZvUNA5UxfvLgdS+V23AzBMjNeoSWOSy8QCkW1" +
        "7HK7ZT8vfm3dyAxIVHiLH38L7piHttnZ/9o5CCk3OSanDCSYlP6p+EDvyj4YdjOOs64PeO4o0SSm0oqhyxlDkP3IwOGuUx+HbVAH" +
        "Y5mp1pT1lFJhpVXhsf78f5rMFvxnD0k8aAV2dVtfVenQakma7ghFg6RWe4MMxd3Ozl3llulkIexaBjwx5YL8ytxYAkyKawEAAQA="

private const val SIG_B64 =
    "DCbtJlzsErgurH7dxmM0MTfoLabsNutdySdfH/Ex+MzDqMJ7sPn8JM+wtBIukcbh/QGgah+CPK3fUHwyv3qALVXaO785uOCQ8uWm" +
        "pOnSolXnTiyBwJ6fDRNtAuTW7KF7VDq47O98gB7l9QFMft56Z7J+SpHvnQ4UreL/ZA2+BMX8zINQ1faUi+DI9z1ube6OQ0s03qyS" +
        "MFvEKABuPnU1RHBaU61yCYt47HIkOFVhWS5jFOJWVjfWdWKuXJXuTU9Yow27CcSQD7bF5yrrl1ktkk+bW23kp6Q59wIv5QAmcrx9" +
        "g6aC4tDjPVBDfOa5sOqn5YAfxfqGTEOYq+7fX9s/vQ=="
