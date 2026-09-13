package com.zcode.remote.core

import org.json.JSONObject

/**
 * relay 桥线上协议的 Kotlin 移植（Tier2 原生任务事件解码的数据面）。
 *
 * 参照实现：`app/src/main/assets/zcode-protocol.js`（壳注入层的同一份代码，
 * 32 项 Node 测试守护），golden vectors 由该实现离线生成并固化在
 * `RelayWireTest`——两层字节必须逐字一致，任何一边改动都要过向量。
 *
 * 三层结构：
 *   relay 信封 `{type:'data', payload:…}`（Tier2Probe/注入层负责）
 *   └ payload = rpc-frame（本文件的分片/重组，CRC32-IEEE 校验）
 *      └ 重组后 = ChannelClient body：`[REQ_*, id, channel, method] + args`
 *         值编解码 tag：0 null | 1 string | 2/3 bytes | 4 array | 5 JSON | 6 int
 *
 * 不移植的部分：页面 RPC 镜像/统计（那是 Tier1 观测用途）、覆盖/冷却策略
 * （Tier1 专属）。这里只做 Tier2 解码任务事件所必需的数据面。
 */
object RelayWire {

    // ChannelClient request/response type tags.
    const val REQ_PROMISE = 100
    const val REQ_PROMISE_CANCEL = 101
    const val REQ_EVENT_LISTEN = 102
    const val REQ_EVENT_DISPOSE = 103
    const val RES_INITIALIZE = 200
    const val RES_PROMISE_SUCCESS = 201
    const val RES_PROMISE_ERROR = 202
    const val RES_PROMISE_ERROR_OBJ = 203
    const val RES_EVENT_FIRE = 204

    const val CHANNEL_CONVERSATION = "zcode-agent"
    const val EVENT_SESSIONS_INDEX = "onDynamicSessionsIndexFrame"
    const val METHOD_SUBSCRIBE_SI = "subscribeSessionsIndexV4"
    const val METHOD_UNSUBSCRIBE_SI = "unsubscribeSessionsIndexV4"
    const val METHOD_RESYNC_SI = "resyncSessionsIndexV4"

    const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024
    const val MAX_FRAGMENT_BYTES = 512 * 1024
    const val MAX_FRAGMENTS = 64
    private const val MAX_VALUE_BYTES = 16 * 1024 * 1024
    private const val MAX_CONTAINER_ITEMS = 100_000

    class WireException(message: String) : Exception(message)

    // ------------------------------------------------------------------ varint

    class ByteWriter {
        private var buf = ByteArray(256)
        var len = 0
            private set

        private fun ensure(extra: Int) {
            if (len + extra <= buf.size) return
            var size = buf.size * 2
            while (size < len + extra) size *= 2
            buf = buf.copyOf(size)
        }

        fun writeByte(value: Int): ByteWriter {
            ensure(1)
            buf[len++] = (value and 0xFF).toByte()
            return this
        }

        /** LEB128 无符号 32 位（与 JS `value >>> 0` 语义一致）。 */
        fun writeVarint(value: Long): ByteWriter {
            ensure(5)
            var v = value and 0xFFFFFFFFL
            do {
                var b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v > 0L) b = b or 0x80
                buf[len++] = b.toByte()
            } while (v > 0L)
            return this
        }

        fun writeBytes(arr: ByteArray): ByteWriter {
            ensure(arr.size)
            arr.copyInto(buf, len)
            len += arr.size
            return this
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)
    }

    class ByteReader(val data: ByteArray) {
        var pos = 0
            private set

        val remaining: Int get() = data.size - pos

        fun readByte(): Int {
            if (pos >= data.size) throw WireException("ByteReader: out of data")
            return data[pos++].toInt() and 0xFF
        }

        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (pos < data.size) {
                val b = readByte()
                if (shift == 28 && (b and 0xF0) != 0) throw WireException("ByteReader: varint overflow")
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value and 0xFFFFFFFFL
                shift += 7
                if (shift > 28) throw WireException("ByteReader: varint overflow")
            }
            throw WireException("ByteReader: truncated varint")
        }

        fun readBytes(n: Int): ByteArray {
            if (n < 0 || pos + n > data.size) throw WireException("ByteReader: cannot read $n bytes")
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }

    // ------------------------------------------------------------------ crc32

    /** CRC32-IEEE（与页面 web 客户端同一表语义），返回无符号 0..2^32-1。 */
    fun crc32(bytes: ByteArray): Long {
        var crc = -1
        for (b in bytes) {
            crc = crcTable[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return (crc.inv()).toLong() and 0xFFFFFFFFL
    }

    fun crc32Hex(bytes: ByteArray): String = "%08x".format(crc32(bytes))

    private val crcTable = IntArray(256) { i ->
        var c = i
        repeat(8) {
            c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
        }
        c
    }

    // ------------------------------------------------------------- value codec

    fun encodeValue(writer: ByteWriter, value: Any?) {
        when (value) {
            null -> writer.writeByte(0)
            is String -> {
                val str = value.toByteArray(Charsets.UTF_8)
                writer.writeByte(1).writeVarint(str.size.toLong()).writeBytes(str)
            }
            is ByteArray -> {
                writer.writeByte(3).writeVarint(value.size.toLong()).writeBytes(value)
            }
            is List<*> -> {
                writer.writeByte(4).writeVarint(value.size.toLong())
                for (item in value) encodeValue(writer, item)
            }
            is Int -> {
                require(value in 0..0x7FFFFFFF) { "value: int out of range $value" }
                writer.writeByte(6).writeVarint(value.toLong())
            }
            is Long -> {
                require(value in 0..0x7FFFFFFF) { "value: int out of range $value" }
                writer.writeByte(6).writeVarint(value)
            }
            // 其余（布尔/浮点/对象）按页面参照走 JSON tag。
            else -> {
                val json = jsonOf(value).toString().toByteArray(Charsets.UTF_8)
                writer.writeByte(5).writeVarint(json.size.toLong()).writeBytes(json)
            }
        }
    }

    private fun jsonOf(value: Any?): Any = when (value) {
        is JSONObject -> value
        is Map<*, *> -> {
            val o = JSONObject()
            for ((k, v) in value) o.put(k.toString(), jsonOf(v))
            o
        }
        is Boolean -> value
        is Double, is Float -> value
        is List<*> -> {
            val arr = org.json.JSONArray()
            for (item in value) arr.put(jsonOf(item))
            arr
        }
        else -> value
    }

    /** JSON 值的长度预计算与顺序无关；页面端 JSON.parse 不在乎键序。 */
    fun decodeValue(reader: ByteReader): Any? {
        return when (val tag = reader.readByte()) {
            0 -> null
            1 -> {
                val len = reader.readVarint().toInt()
                if (len > MAX_VALUE_BYTES) throw WireException("value: string too large")
                String(reader.readBytes(len), Charsets.UTF_8)
            }
            2, 3 -> {
                val n = reader.readVarint().toInt()
                if (n > MAX_VALUE_BYTES) throw WireException("value: bytes too large")
                reader.readBytes(n)
            }
            4 -> {
                val count = reader.readVarint().toInt()
                if (count > MAX_CONTAINER_ITEMS) throw WireException("value: array too large")
                val arr = ArrayList<Any?>(count)
                for (i in 0 until count) arr.add(decodeValue(reader))
                arr
            }
            5 -> {
                val jlen = reader.readVarint().toInt()
                if (jlen > MAX_VALUE_BYTES) throw WireException("value: object too large")
                JSONObject(String(reader.readBytes(jlen), Charsets.UTF_8))
            }
            6 -> reader.readVarint().toInt()
            else -> throw WireException("value: unknown tag $tag")
        }
    }

    fun encodeBody(header: List<Any?>, value: Any?): ByteArray {
        val writer = ByteWriter()
        encodeValue(writer, header)
        encodeValue(writer, value)
        return writer.toByteArray()
    }

    // ---------------------------------------------------------- rpc-frame 层

    /**
     * 出站分片（对应 JS RpcFrameSender.sendMessage）：seq 从 [nextSeq] 起自增，
     * 返回 rpc-frame payload 列表（发送方逐个包进 relay 信封发出）。
     */
    fun fragmentMessage(
        bytes: ByteArray,
        bridgeSessionId: String,
        messageSeq: Long,
        nextSeq: Long,
        bridgeGeneration: Long? = null,
        recoveryId: String? = null,
        maxFragmentBytes: Int = MAX_FRAGMENT_BYTES,
    ): List<JSONObject> {
        if (bytes.isEmpty()) throw WireException("rpcFrame: empty message")
        if (bytes.size > MAX_MESSAGE_BYTES) throw WireException("rpcFrame: message too large")
        val checksum = crc32Hex(bytes)
        val fragmentCount = (bytes.size + maxFragmentBytes - 1) / maxFragmentBytes
        if (fragmentCount > MAX_FRAGMENTS) throw WireException("rpcFrame: too many fragments")
        var seq = nextSeq
        val out = ArrayList<JSONObject>(fragmentCount)
        for (i in 0 until fragmentCount) {
            val start = i * maxFragmentBytes
            val end = minOf(start + maxFragmentBytes, bytes.size)
            val payload = JSONObject()
                .put("zcode_type", "rpc-frame")
                .put("bridgeSessionId", bridgeSessionId)
                .put("seq", seq)
                .put("messageSeq", messageSeq)
                .put("fragmentIndex", i)
                .put("fragmentCount", fragmentCount)
                .put("messageBytes", bytes.size)
                .put("checksum", JSONObject().put("algorithm", "crc32").put("value", checksum))
                .put(
                    "dataBase64",
                    java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(start, end)),
                )
            if (bridgeGeneration != null) payload.put("bridgeGeneration", bridgeGeneration)
            if (!recoveryId.isNullOrBlank()) payload.put("recoveryId", recoveryId)
            out.add(payload)
            seq += 1
        }
        return out
    }

    /**
     * 入站重组（对应 JS RpcFrameAssembler）：乱序/分批到达均可，完整即校验交付。
     * 交付前发出 ack（调用方包成 rpc-frame-ack 信封回给桌面端）。
     */
    class FrameAssembler(
        private val bridgeSessionId: String,
        private val onLog: (String) -> Unit = {},
    ) {
        private class Assembly(
            val fragmentCount: Int,
            val messageBytes: Int,
            val checksum: String?,
            val fragments: Array<ByteArray?>,
        ) {
            var received = 0
        }

        private val assemblies = HashMap<Long, Assembly>()

        data class Delivered(val message: ByteArray, val messageSeq: Long)

        /**
         * @return 喂给桌面端的帧完整交付时返回 [Delivered]（调用方随后回
         *   rpc-frame-ack）；信封不归属本桥/类型不符时返回 null。
         */
        fun accept(payload: JSONObject): Delivered? {
            if (payload.optString("bridgeSessionId") != bridgeSessionId) return null
            when (payload.optString("zcode_type")) {
                "rpc-frame-ack" -> return null
                "rpc-frame" -> Unit
                else -> return null
            }
            val messageSeq = payload.optLong("messageSeq", -1L)
            val fragmentIndex = payload.optInt("fragmentIndex", -1)
            val fragmentCount = payload.optInt("fragmentCount", -1)
            val messageBytes = payload.optInt("messageBytes", -1)
            val dataBase64 = payload.optString("dataBase64")
            if (messageSeq < 0 || fragmentIndex < 0 || fragmentCount < 1 ||
                fragmentCount > MAX_FRAGMENTS || fragmentIndex >= fragmentCount ||
                messageBytes < 1 || messageBytes > MAX_MESSAGE_BYTES || dataBase64.isEmpty()
            ) {
                return null
            }
            val chunk = try {
                java.util.Base64.getDecoder().decode(dataBase64)
            } catch (e: Exception) {
                return null
            }
            if (chunk.size > MAX_FRAGMENT_BYTES) return null
            val checksum = payload.optJSONObject("checksum")?.optString("value")
            val existing = assemblies[messageSeq]
            if (existing != null &&
                (existing.fragmentCount != fragmentCount ||
                    existing.messageBytes != messageBytes ||
                    existing.checksum != checksum)
            ) {
                assemblies.remove(messageSeq)
                return null
            }
            val assembly = existing ?: Assembly(fragmentCount, messageBytes, checksum, arrayOfNulls(fragmentCount))
                .also { assemblies[messageSeq] = it }
            if (assembly.fragments[fragmentIndex] == null) assembly.received += 1
            assembly.fragments[fragmentIndex] = chunk
            if (assembly.received != assembly.fragmentCount) return null
            assemblies.remove(messageSeq)
            val message = assembly.fragments.reduce { acc, part -> acc + (part ?: ByteArray(0)) }
            if (message.size != assembly.messageBytes) {
                onLog("rpc message $messageSeq size mismatch")
                return null
            }
            if (!assembly.checksum.isNullOrEmpty() && crc32Hex(message) != assembly.checksum) {
                onLog("rpc message $messageSeq checksum mismatch")
                return null
            }
            return Delivered(message, messageSeq)
        }
    }

    // ------------------------------------------------------- ChannelClient body

    /** 组装一个 promise 请求 body：`[REQ_PROMISE, id, channel, method] + args`。 */
    fun requestBody(id: Long, channel: String, method: String, args: List<Any?>): ByteArray =
        encodeBody(listOf(REQ_PROMISE, id, channel, method), args)

    /** 组装一个事件监听 body：`[REQ_EVENT_LISTEN, id, channel, event] + args`。 */
    fun listenBody(id: Long, channel: String, event: String, args: List<Any?>): ByteArray =
        encodeBody(listOf(REQ_EVENT_LISTEN, id, channel, event), args)

    /** 解析 ChannelClient body：header 数组 + 可选 args。 */
    class ParsedBody(val header: List<Any?>, val args: Any?)

    fun parseBody(bytes: ByteArray): ParsedBody {
        val reader = ByteReader(bytes)
        val header = decodeValue(reader)
        if (header !is List<*>) throw WireException("body: header not an array")
        val args = if (reader.remaining > 0) decodeValue(reader) else null
        @Suppress("UNCHECKED_CAST")
        return ParsedBody(header as List<Any?>, args)
    }
}
