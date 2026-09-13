package com.zcode.remote.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RelayWire 与页面参照实现（zcode-protocol.js）的字节级对照。
 * golden vectors 由该 JS 实现离线生成（2026-09-13，node 直载），
 * 任何一边的编码改动都必须让这里全绿——两边是同一条线上的两端。
 */
class RelayWireTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `hello request body matches the reference encoder`() {
        val body = RelayWire.encodeBody(
            listOf(RelayWire.REQ_PROMISE, 1, RelayWire.CHANNEL_CONVERSATION, "helloConversationV4"),
            emptyList<Any?>(),
        )
        assertEquals(
            "040406640601010b7a636f64652d6167656e74011368656c6c6f436f6e766572736174696f6e56340400",
            hex(body),
        )
    }

    @Test
    fun `subscribe request with scope object matches`() {
        // scope 是单键 JSON 对象——编码走自带 writer（JS stringify 语义，
        // 不依赖 org.json 的序列化行为），与 JS 参照字节一致。
        val scope = linkedMapOf<String, Any?>("workspacePath" to "/repo/x")
        val body = RelayWire.encodeBody(
            listOf(RelayWire.REQ_PROMISE, 2, RelayWire.CHANNEL_CONVERSATION, RelayWire.METHOD_SUBSCRIBE_SI),
            listOf(scope),
        )
        assertEquals(
            "040406640602010b7a636f64652d6167656e74000401051b7b22776f726b737061636550617468223a222f7265706f2f78227d",
            hex(body),
        )
    }

    @Test
    fun `promise success reply with nested ack matches`() {
        val body = RelayWire.encodeBody(
            listOf(RelayWire.RES_PROMISE_SUCCESS, 5),
            linkedMapOf<String, Any?>("ack" to linkedMapOf<String, Any?>("subscriptionId" to "sub-1")),
        )
        assertEquals(
            "040206c901060505227b2261636b223a7b22737562736372697074696f6e4964223a227375622d31227d7d",
            hex(body),
        )
    }

    @Test
    fun `listen body matches`() {
        val body = RelayWire.encodeBody(
            listOf(RelayWire.REQ_EVENT_LISTEN, 9, RelayWire.CHANNEL_CONVERSATION, RelayWire.EVENT_SESSIONS_INDEX),
            listOf(linkedMapOf<String, Any?>("workspaceIdentity" to "ws-a")),
        )
        assertEquals(
            "040406660609010b7a636f64652d6167656e74011b6f6e44796e616d696353657373696f6e73496e6465784672616d650401051c7b22776f726b73706163654964656e74697479223a2277732d61227d",
            hex(body),
        )
    }

    @Test
    fun `varint encodes the 28-bit maximum like the reference`() {
        val body = RelayWire.encodeBody(
            listOf(RelayWire.REQ_PROMISE, 268435455, "zcode-agent", "m"),
            null,
        )
        assertEquals(
            "0404066406ffffff7f010b7a636f64652d6167656e7401016d00",
            hex(body),
        )
    }

    @Test
    fun `event fire snapshot decodes structurally`() {
        // JS 参照生成的完整 sessions-index fire 帧（多键 JSON）——解码侧只认内容。
        val bytes = hexToBytes(
            "040206cc01060705a8017b22746f706963223a2273657373696f6e732d696e6465782f77732d61222c226b696e64223a22636f6d706c657465222c226672616d65223a7b22746f536571223a312c227061796c6f6164223a7b226b696e64223a22736e617073686f74222c22736e617073686f74223a7b22776f726b73706163654964223a2277732d61222c226c6f6745706f6368223a2265706f63682d31222c2273657373696f6e73223a5b5d7d7d7d7d",
        )
        val parsed = RelayWire.parseBody(bytes)
        assertEquals(RelayWire.RES_EVENT_FIRE, parsed.header[0])
        assertEquals(7L, (parsed.header[1] as Int).toLong())
        val wire = parsed.args as JSONObject
        assertEquals("sessions-index/ws-a", wire.getString("topic"))
        assertEquals("complete", wire.getString("kind"))
        val snapshot = wire.getJSONObject("frame").getJSONObject("payload").getJSONObject("snapshot")
        assertEquals("ws-a", snapshot.getString("workspaceId"))
        assertEquals("epoch-1", snapshot.getString("logEpoch"))
        assertEquals(0, snapshot.getJSONArray("sessions").length())
    }

    @Test
    fun `round trip preserves header and args`() {
        val header = listOf<Any?>(
            RelayWire.RES_PROMISE_SUCCESS, 12, "text", "with 中文", 0, null,
        )
        val args = listOf<Any?>(
            JSONObject().put("k", "v"), "plain", 42, null,
        )
        val bytes = RelayWire.encodeBody(header, args)
        val parsed = RelayWire.parseBody(bytes)
        assertEquals(header[0], parsed.header[0])
        assertEquals(header[1], parsed.header[1])
        assertEquals(header[2], parsed.header[2])
        assertEquals(header[3], parsed.header[3])
        assertEquals(header[4], parsed.header[4])
        assertEquals(header[5], parsed.header[5])
        val parsedArgs = parsed.args as List<*>
        assertEquals("v", (parsedArgs[0] as JSONObject).getString("k"))
        assertEquals("plain", parsedArgs[1])
        assertEquals(42, parsedArgs[2])
        assertEquals(null, parsedArgs[3])
    }

    @Test
    fun `crc32 matches the reference vectors`() {
        assertEquals(0x00000000L, RelayWire.crc32(ByteArray(0)))
        assertEquals("00000000", RelayWire.crc32Hex(ByteArray(0)))
        assertEquals("352441c2", RelayWire.crc32Hex("abc".toByteArray(Charsets.UTF_8)))
        assertEquals("0d4a1185", RelayWire.crc32Hex("hello world".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `fragments reassemble out of order with checksum verified`() {
        val message = ByteArray(20) { it.toByte() }
        val frames = RelayWire.fragmentMessage(message, "bridge-1", messageSeq = 3, nextSeq = 10, maxFragmentBytes = 8)
        assertEquals(3, frames.size)
        val assembler = RelayWire.FrameAssembler("bridge-1")
        var delivered: RelayWire.FrameAssembler.Delivered? = null
        for (index in intArrayOf(2, 0, 1)) {
            delivered = assembler.accept(frames[index])
            if (index == 1) assertNotNull(delivered)
        }
        assertNotNull(delivered)
        assertEquals(3L, delivered!!.messageSeq)
        assertArrayEquals(message, delivered.message)
    }

    @Test
    fun `foreign bridge and non-frame payloads are ignored`() {
        val assembler = RelayWire.FrameAssembler("bridge-1")
        val frames = RelayWire.fragmentMessage(byteArrayOf(1, 2, 3), "bridge-other", messageSeq = 1, nextSeq = 1)
        assertNull(assembler.accept(frames[0]))
        assertNull(assembler.accept(JSONObject().put("zcode_type", "workspace-bridge-ready")))
    }

    @Test
    fun `checksum mismatch drops the message`() {
        val logs = mutableListOf<String>()
        val assembler = RelayWire.FrameAssembler("bridge-1", onLog = { logs.add(it) })
        val frames = RelayWire.fragmentMessage(byteArrayOf(9, 8, 7), "bridge-1", messageSeq = 1, nextSeq = 1)
        // 篡改分片数据（重组会完整但校验不过）。
        frames[0].put(
            "dataBase64",
            java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 6)),
        )
        assertNull(assembler.accept(frames[0]))
        assertTrue(logs.any { it.contains("checksum mismatch") })
    }

    @Test
    fun `conflicting reassembly resets the message`() {
        val assembler = RelayWire.FrameAssembler("bridge-1")
        val first = RelayWire.fragmentMessage(byteArrayOf(1, 2, 3), "bridge-1", messageSeq = 1, nextSeq = 1, maxFragmentBytes = 8)
        assembler.accept(first[0])
        // 同 messageSeq 但 fragmentCount 不同的第二个分片 → 现有组装作废。
        val second = RelayWire.fragmentMessage(byteArrayOf(4, 5, 6, 7, 8), "bridge-1", messageSeq = 1, nextSeq = 2, maxFragmentBytes = 2)
        assertNull(assembler.accept(second[0]))
        // 重新喂完整的第二组 → 正常交付。
        var delivered: RelayWire.FrameAssembler.Delivered? = null
        for (frame in second) delivered = assembler.accept(frame)
        assertNotNull(delivered)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7, 8), delivered!!.message)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }
}
