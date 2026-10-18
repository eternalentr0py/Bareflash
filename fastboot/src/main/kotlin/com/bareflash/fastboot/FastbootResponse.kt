package com.bareflash.fastboot

/**
 * One packet from the device. Every response starts with a 4-character ASCII
 * prefix; INFO/OKAY/FAIL carry text, DATA carries 8 hex digits (the number of
 * bytes the device is ready to receive).
 */
public sealed interface FastbootResponse {
    public class Info(public val message: String) : FastbootResponse
    public class Okay(public val message: String) : FastbootResponse
    public class Fail(public val message: String) : FastbootResponse
    public class Data(public val length: Long) : FastbootResponse

    public companion object {
        /** Parses the first [length] bytes of [buffer]. Throws [FastbootProtocolException] if malformed. */
        public fun parse(buffer: ByteArray, length: Int): FastbootResponse {
            if (length < 4) throw FastbootProtocolException("Response too short ($length bytes)")
            val prefix = String(buffer, 0, 4, Charsets.US_ASCII)
            val text = String(buffer, 4, length - 4, Charsets.UTF_8)
            return when (prefix) {
                "INFO" -> Info(text)
                "OKAY" -> Okay(text)
                "FAIL" -> Fail(text)
                "DATA" -> {
                    if (length != 12 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                        throw FastbootProtocolException("Malformed DATA response")
                    }
                    Data(text.toLong(16))
                }
                else -> throw FastbootProtocolException("Unknown response prefix")
            }
        }
    }
}
