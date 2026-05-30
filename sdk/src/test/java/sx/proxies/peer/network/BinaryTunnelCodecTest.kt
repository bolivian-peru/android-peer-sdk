package sx.proxies.peer.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-correctness tests for the binary tunnel codec (C1). These pin the
 * wire layout the relay depends on and exercise the decode path that feeds
 * the per-session ordered writer.
 */
class BinaryTunnelCodecTest {

    @Test
    fun encode_layout_isTypeLenSidPayload() {
        val frame = BinaryTunnelCodec.encode(
            BinaryTunnelCodec.MSG_TUNNEL_DATA,
            "abc",
            byteArrayOf(9, 8, 7),
        )
        // [type][sidLen][s][i][d...][payload...]
        assertEquals(BinaryTunnelCodec.MSG_TUNNEL_DATA, frame[0])
        assertEquals(3, frame[1].toInt())
        assertArrayEquals("abc".toByteArray(), frame.copyOfRange(2, 5))
        assertArrayEquals(byteArrayOf(9, 8, 7), frame.copyOfRange(5, frame.size))
    }

    @Test
    fun roundTrip_preservesTypeSessionAndPayload() {
        val payload = ByteArray(1000) { (it % 256).toByte() }
        val encoded = BinaryTunnelCodec.encode(BinaryTunnelCodec.MSG_TUNNEL_DATA, "session-42", payload)
        val decoded = BinaryTunnelCodec.decode(encoded)!!
        assertEquals(BinaryTunnelCodec.MSG_TUNNEL_DATA, decoded.type)
        assertEquals("session-42", decoded.sessionId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun roundTrip_emptyPayload() {
        val encoded = BinaryTunnelCodec.encode(BinaryTunnelCodec.MSG_TUNNEL_CLOSE, "s", ByteArray(0))
        val decoded = BinaryTunnelCodec.decode(encoded)!!
        assertEquals(BinaryTunnelCodec.MSG_TUNNEL_CLOSE, decoded.type)
        assertEquals("s", decoded.sessionId)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun encode_honorsPayloadLenWindow() {
        val buffer = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        // Only the first 4 bytes are "real" (simulating a partial socket read).
        val encoded = BinaryTunnelCodec.encode(BinaryTunnelCodec.MSG_TUNNEL_DATA, "x", buffer, payloadLen = 4)
        val decoded = BinaryTunnelCodec.decode(encoded)!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded.payload)
    }

    @Test
    fun decode_returnsNull_onTruncatedFrames() {
        assertNull(BinaryTunnelCodec.decode(ByteArray(0)))
        assertNull(BinaryTunnelCodec.decode(byteArrayOf(BinaryTunnelCodec.MSG_TUNNEL_DATA)))
        // Claims sidLen=5 but only 2 sid bytes present.
        assertNull(BinaryTunnelCodec.decode(byteArrayOf(BinaryTunnelCodec.MSG_TUNNEL_DATA, 5, 'a'.code.toByte(), 'b'.code.toByte())))
    }

    @Test
    fun decode_ofSequentialFrames_yieldsPayloadsInOrder() {
        // The relay reader decodes frames one at a time, in arrival order; the
        // writer must then receive payloads in that same order. Verify decode
        // is order-preserving across a stream of frames for one session.
        val sid = "ordered"
        val chunks = (0 until 50).map { byteArrayOf(it.toByte(), (it * 2).toByte()) }
        val decodedPayloads = chunks
            .map { BinaryTunnelCodec.encode(BinaryTunnelCodec.MSG_TUNNEL_DATA, sid, it) }
            .map { BinaryTunnelCodec.decode(it)!!.payload }
        for (i in chunks.indices) {
            assertArrayEquals("chunk $i out of order", chunks[i], decodedPayloads[i])
        }
    }

    @Test
    fun encode_rejectsOversizedSessionId() {
        val tooLong = "x".repeat(256)
        val threw = try {
            BinaryTunnelCodec.encode(BinaryTunnelCodec.MSG_TUNNEL_DATA, tooLong, ByteArray(1))
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("expected IllegalArgumentException for >255-byte sessionId", threw)
    }

    @Test
    fun utf8SessionId_roundTrips() {
        val sid = "café-π-😀"
        val decoded = BinaryTunnelCodec.decode(
            BinaryTunnelCodec.encode(BinaryTunnelCodec.MSG_TUNNEL_DATA, sid, byteArrayOf(1)),
        )!!
        assertEquals(sid, decoded.sessionId)
    }
}
