package sx.proxies.peer.network

/**
 * Wire codec for the binary tunnel protocol (v1.2.0+). Pure / Android-free so
 * it can be unit-tested directly.
 *
 * Frame layout (matches relay-server encodeBinaryTunnelData byte-for-byte):
 *
 *   [0]            type      (MSG_TUNNEL_DATA / MSG_TUNNEL_CLOSE)
 *   [1]            sidLen    (UTF-8 byte count of sessionId, ≤ 255)
 *   [2..2+sidLen)  sessionId (UTF-8 bytes)
 *   [2+sidLen..]   payload   (raw bytes, no base64 / JSON)
 */
object BinaryTunnelCodec {
    const val MSG_TUNNEL_DATA: Byte = 0x01
    const val MSG_TUNNEL_CLOSE: Byte = 0x03

    /** Max sessionId length encodable in the single-byte length field. */
    const val MAX_SESSION_ID_BYTES = 255

    data class Frame(val type: Byte, val sessionId: String, val payload: ByteArray) {
        // Value-equality on the byte array (data class would compare identity).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return type == other.type &&
                sessionId == other.sessionId &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = type.toInt()
            result = 31 * result + sessionId.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    fun encode(
        type: Byte,
        sessionId: String,
        payload: ByteArray,
        payloadLen: Int = payload.size,
    ): ByteArray {
        val sid = sessionId.toByteArray(Charsets.UTF_8)
        require(sid.size <= MAX_SESSION_ID_BYTES) { "sessionId too long for binary frame" }
        require(payloadLen in 0..payload.size) { "invalid payloadLen" }
        val out = ByteArray(2 + sid.size + payloadLen)
        out[0] = type
        out[1] = sid.size.toByte()
        System.arraycopy(sid, 0, out, 2, sid.size)
        System.arraycopy(payload, 0, out, 2 + sid.size, payloadLen)
        return out
    }

    /** Decode one frame, or null if [buf] is too short to be a valid frame. */
    fun decode(buf: ByteArray): Frame? {
        if (buf.size < 2) return null
        val type = buf[0]
        val sidLen = buf[1].toInt() and 0xFF
        if (buf.size < 2 + sidLen) return null
        val sessionId = String(buf, 2, sidLen, Charsets.UTF_8)
        val payload = buf.copyOfRange(2 + sidLen, buf.size)
        return Frame(type, sessionId, payload)
    }
}
