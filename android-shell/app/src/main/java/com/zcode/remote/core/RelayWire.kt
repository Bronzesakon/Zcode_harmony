package com.zcode.remote.core

import org.json.JSONArray
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

    /**
     * 对话详情尾窗（**已判定为死路，保留常量只作记录**）。
     *
     * 请求 `{...workspaceScope, sessionId, limit}` → 应答 `{rows, atSeq, atLogEpoch,
     * hasMore}`，行结构与 `onDynamicConversationFrame` 里的行完全同构。
     * 真机实测（pre.84/85/87）：原生侧调用**每次都 20s 超时**，带上 workspace
     * scope 也一样，订阅建立之后也一样——桌面端就是不回这个包。改用下面的
     * 订阅通道（[METHOD_SUBSCRIBE_CONV]）取快照，别再回到拉取这条路。
     */
    const val METHOD_ROWS_RANGE = "conversationRowsRangeV4"

    /**
     * 对话详情**订阅**（M4 的主通道，已在真机验证）。页面侧同一套：
     * `subscribeConversationV4({...workspaceScope, sessionId, visibility?})` →
     * `ack.subscriptionId`，帧走事件 `onDynamicConversationFrame`（物理信封 →
     * 逻辑帧 `{topic, subscriptionId, fromSeq, toSeq, payload:{kind:'snapshot'|'deltas'}}`；
     * `deltas` 的 op 有 `row.appended` / `row.upserted` / `row.removed` /
     * `row.delta{rowId,path,append}` / `state.updated`）。
     *
     * 订阅一建立，桌面端立刻推一份 snapshot（整窗行）——这就是"最新进展"的第一
     * 手来源；此后靠 `row.delta` 跟进，再由壳侧周期重挂兜住推送稀疏。
     */
    const val EVENT_CONVERSATION_FRAME = "onDynamicConversationFrame"
    const val METHOD_SUBSCRIBE_CONV = "subscribeConversationV4"
    const val METHOD_UNSUBSCRIBE_CONV = "unsubscribeConversationV4"
    const val METHOD_RESYNC_CONV = "resyncConversationV4"

    /**
     * controller 流（**运行态的唯一正源**）。
     *
     * `sessions-index` 的 `phase` 只在轮次边界变（"完成"是持久态），真正的
     * "此刻在跑"由 `controller/tasks-index` 的任务条目 `liveStatus` 承载。
     * 网页端由 `windowControllerTaskListRegistry` 订阅两条 controller topic
     * （`controller/workspaces` + `controller/tasks-index`），帧走
     * `onDynamicControllerFrame`，缺口用 `resyncControllerV4` 补。
     */
    const val TOPIC_CONTROLLER_TASKS = "controller/tasks-index"
    const val EVENT_CONTROLLER_FRAME = "onDynamicControllerFrame"
    const val METHOD_SUBSCRIBE_CONTROLLER = "subscribeControllerV4"
    const val METHOD_UNSUBSCRIBE_CONTROLLER = "unsubscribeControllerV4"
    const val METHOD_RESYNC_CONTROLLER = "resyncControllerV4"

    const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024
    /**
     * 单帧字节上限 = 桌面端的 `maxFrameBytes`（1024*1024）。
     *
     * 曾经是 512KiB（桌面端的一半）：出站没问题，但**入站**的
     * `FrameAssembler` 用同一个常量卡 512KiB~1MiB 的帧，会静默丢弃大快照的
     * 分片（bundle `maxFrameBytes:1024*1024`，2026-09-14 审计修正）。
     */
    const val MAX_FRAGMENT_BYTES = 1024 * 1024
    const val MAX_FRAGMENTS = 64

    /**
     * 逻辑帧的分片数上限：协议允许到 1024（bundle
     * `logicalFrameAssemblyMaxFragments:1024`），远高于物理帧的 64。用 64 去卡
     * 逻辑帧会把长会话的大快照整份丢掉。
     */
    const val MAX_LOGICAL_FRAGMENTS = 1024
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
            // 其余（布尔/浮点/对象）按页面参照走 JSON tag。JSON 文本由本文件
            // 的 writer 生成（JS JSON.stringify 语义：不转义 '/'），不依赖
            // org.json——Android 与 JVM 的 org.json 序列化行为不一致，
            // golden vectors 必须两端逐字相同。
            else -> writeJsonTag(writer, writeJsonValue(value))
        }
    }

    private fun writeJsonTag(writer: ByteWriter, jsonText: String) {
        val json = jsonText.toByteArray(Charsets.UTF_8)
        writer.writeByte(5).writeVarint(json.size.toLong()).writeBytes(json)
    }

    private fun writeJsonValue(value: Any?): String {
        val sb = StringBuilder()
        writeJsonInto(sb, value)
        return sb.toString()
    }

    private fun writeJsonInto(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeJsonString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeJsonString(sb, k.toString())
                    sb.append(':')
                    writeJsonInto(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeJsonInto(sb, item)
                }
                sb.append(']')
            }
            is JSONObject -> sb.append(value.toString())
            else -> writeJsonString(sb, value.toString())
        }
    }

    /** JSON 字符串转义，与 JS JSON.stringify 一致（不转义 '/'）。 */
    private fun writeJsonString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
        }
        sb.append('"')
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
            val parts = assembly.fragments.map { requireNotNull(it) { "missing fragment" } }
            val message = parts.reduce { acc, part -> acc + part }
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

    // ------------------------------------------------------- 对话详情 → 进展文本

    /**
     * 对话详情的逻辑帧装配：物理信封 → 逻辑帧。
     *
     * `kind='complete'` 直接给 `frame`；`kind='fragment'` 按 `logicalFrameId`
     * 缓存 `dataBase64` 分片，凑齐后 base64 解码成 JSON 文本再解析（对应页面
     * `_Te` 里的 begin/bind/accept 分片重组）。凑不齐返回 null——下一帧再来。
     */
    class LogicalFrameAssembler {
        private class Pending(
            val count: Int,
            val parts: Array<ByteArray?>,
        ) {
            var received = 0
        }

        private val pending = HashMap<String, Pending>()

        fun acceptEnvelope(envelope: JSONObject?): JSONObject? {
            val env = envelope ?: return null
            when (env.optString("kind")) {
                "complete" -> return env.optJSONObject("frame")
                "fragment" -> {
                    val id = env.optString("logicalFrameId")
                    val count = env.optInt("fragmentCount", 0)
                    val index = env.optInt("fragmentIndex", -1)
                    if (id.isEmpty() || count <= 0 || count > MAX_LOGICAL_FRAGMENTS ||
                        index < 0 || index >= count
                    ) return null
                    val encoded = env.optString("dataBase64")
                    if (encoded.isEmpty()) return null
                    val bytes = try {
                        java.util.Base64.getDecoder().decode(encoded)
                    } catch (e: Exception) {
                        return null
                    }
                    val current = pending[id]
                    if (current != null && current.count != count) {
                        pending.remove(id)
                        return null
                    }
                    val assembly = current ?: Pending(count, arrayOfNulls(count)).also { pending[id] = it }
                    if (assembly.parts[index] == null) assembly.received += 1
                    assembly.parts[index] = bytes
                    if (assembly.received != count) return null
                    pending.remove(id)
                    val joined = assembly.parts.flatMap { it?.asList().orEmpty() }.toByteArray()
                    val logicalBytes = env.optInt("logicalBytes", -1)
                    if (logicalBytes >= 0 && joined.size != logicalBytes) return null
                    val expected = env.optJSONObject("checksum")?.optString("value")
                    if (!expected.isNullOrEmpty() && crc32Hex(joined) != expected) return null
                    return try {
                        JSONObject(String(joined, Charsets.UTF_8))
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            return null
        }
    }
    /**
     * 从 `conversationRowsRangeV4` 的 rows 里取出"当前进展"那一行文字。
     *
     * 行是按 `kind` 判别的联合（快照 docs/05 `src-DHgFesxz.js` @100327–@105291）：
     * `assistantText{text,state}` / `reasoning{text,state}` / `toolCall{toolName,status,…}` /
     * `subagent{summaryText,status,subagentType}` / `turnHeader{state}` / `userInput{text}` /
     * `hookInvocation` / `timelineMarker`。
     *
     * 取法：**从最新一行往前找第一行能给出"进展"的**——
     *   * `assistantText` → `text`（流式期间由 `row.delta path=text` 持续加长，
     *     所以拉到的就是此刻正在写的正文）；
     *   * `toolCall` / `subagent` / `reasoning` / `userInput` / `turnHeader` /
     *     其它一律跳过——见 [progressTextFromRow] 的真机教训。
     *
     * 全都给不出人话时返回 null：调用方要保留原有 preview，**不要**拿空串覆盖。
     */
    fun progressTextFromRows(rows: JSONArray?): String? {
        if (rows == null || rows.length() == 0) return null
        for (i in rows.length() - 1 downTo 0) {
            val row = rows.optJSONObject(i) ?: continue
            val text = progressTextFromRow(row)
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /**
     * 卡片正文只认 assistantText：与网页会话列表的 lastAssistantPreview 同语义。
     *
     * 真机反馈（2026-09-14 早）：把「正在执行 <工具名>」写上流体云，用户看到的
     * 是对话输出**之外**的东西（tool call 的执行状态），观感像跑题。工具调用与
     * 子代理行一律不上卡片——当前轮还没有正文时，tail 里仍保留着上一轮的
     * assistantText，卡片沿用之（正是网页会话列表的行为）。
     */
    private fun progressTextFromRow(row: JSONObject): String? = when (row.optString("kind")) {
        "assistantText" -> progressHead(row.optString("text"))
        else -> null
    }

    /**
     * 进展文本太长时留**开头**（用户 2026-09-15 明确要求：**从消息开头开始取字**，
     * 不是取尾巴）。
     *
     * ⚠️ 这里原先取的是尾巴，理由记在下面，想改回去之前请先读完：
     * 真机教训（2026-09-13 晚）：原样取整段正文时，流体云卡片的可见部分永远是这段
     * 消息的**开头**——消息在涨、可见内容却不变，用户看着就是"又卡住了"（pre.87 那次
     * 73 分钟静默的成因之一正是"文案对、看不出在动"）。当时因此改成取尾巴。
     *
     * **取开头会把那个代价重新带回来**：一段正文越长，卡片前半段越久不变。现在可以
     * 接受的理由：① 用户要的是"能读到这轮在说什么"，不是"看出它在动"；② 被提升的
     * 卡片默认展开，可见字数比折叠态多；③ 状态栏芯片（运行中/等待确认）与常驻通知的
     * 更新仍在动，不是整体静止。**若真机再出现"卡片像卡住"，第一个该回滚的就是这里。**
     */
    private fun progressHead(text: String): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        if (collapsed.length <= PROGRESS_MAX_CHARS) return collapsed
        return collapsed.substring(0, PROGRESS_MAX_CHARS - 1) + "…"
    }

    /**
     * 公开入口：[progressHead] 的对外形态。
     *
     * 页面自带对话流送来的正文（注入层解出来后走 `convtext` 消息）也用它压成一行，
     * 好让"原生接管"与"页面自带流"两条数据源落进卡片时是**同一套口径**。
     */
    fun progressHeadOf(text: String): String = progressHead(text)

    /** 卡片正文的字符上限（超出只看开头）。 */
    private const val PROGRESS_MAX_CHARS = 120

    // ------------------------------------- 对话详情：逻辑帧的行窗口（M4 订阅面）

    /**
     * 对话详情逻辑帧的行窗口 + `deltas` 归并。
     *
     * 只维护"最后若干行"（[TAIL_ROWS]）——流体云要的是最新一行，不是整段历史，
     * 所以不需要照搬页面的整台 conversation store（它还要管 availability、
     * plan、usage、queue 等一整套状态）。
     *
     * 已实现的 op：`row.appended` / `row.upserted` / `row.removed` /
     * `row.delta`（`text` / `inputText` / `output.text` / `summaryText`）/
     * `state.updated`（会话级元数据，不改行）。缺任何一个都不影响"最新一行"的正确性，
     * 因为下一次 snapshot（重订阅/重同步）会把整窗拉回来重置。
     */
    class ConversationTail {
        private var rows = JSONArray()
        private var currentLogEpoch: String? = null
        private var currentSeq = 0L

        fun logEpoch(): String? = currentLogEpoch
        fun seq(): Long = currentSeq

        val size: Int get() = rows.length()

        fun applySnapshot(snapshot: JSONObject?, logEpoch: String? = null, seq: Long? = null) {
            val window = snapshot?.optJSONObject("rows")?.optJSONArray("window")
            rows = window ?: JSONArray()
            currentLogEpoch = logEpoch?.takeIf { it.isNotEmpty() }
                ?: snapshot?.optString("logEpoch")?.takeIf { it.isNotEmpty() }
            if (seq != null) currentSeq = seq
            trim()
        }

        fun applyDeltas(deltas: JSONArray?, seq: Long? = null) {
            if (deltas == null) return
            for (i in 0 until deltas.length()) {
                val op = deltas.optJSONObject(i) ?: continue
                when (op.optString("op")) {
                    "row.appended" -> {
                        op.optJSONObject("row")?.let { rows.put(it) }
                    }
                    "row.upserted" -> {
                        val row = op.optJSONObject("row") ?: continue
                        val at = indexOfRow(row.optString("rowId"))
                        if (at >= 0) rows.put(at, row)
                    }
                    "row.removed" -> {
                        val fromRowId = op.optString("fromRowId")
                        if (fromRowId.isEmpty()) {
                            rows = JSONArray()
                        } else {
                            val at = indexOfRow(fromRowId)
                            rows = when {
                                at < 0 -> rows
                                at == 0 -> JSONArray()
                                else -> {
                                    val kept = JSONArray()
                                    for (j in 0 until at) kept.put(rows.opt(j))
                                    kept
                                }
                            }
                        }
                    }
                    "row.delta" -> applyRowDelta(op)
                    "state.updated" -> applyStatePatch(op)
                }
            }
            if (seq != null) currentSeq = seq
            trim()
        }

        /** 最新一行的"进展"文本；没有可展示的行时返回 null。 */
        fun latestProgressText(): String? = progressTextFromRows(rows)

        /**
         * 会话尾窗里的**运行态**：最后一条 `turnHeader` 的 `state`。
         *
         * 这是 Tier2 后台跟踪的可靠运行态来源——`sessions-index` 的 `phase` 只在
         * 轮次边界变，`controller/tasks-index` 又拿不到（真机 `subscribeControllerV4
         * timed out`，那条流看来由桌面端窗口进程提供，而接管恰好顶掉页面），而
         * 会话流本身一直跟着最新状态：`turnHeader.state == 'running'` 就是"在跑"
         * （bundle 里 UI 也是这么判的：`_?.state === 'running'`）。
         *
         * 返回 null = 尾窗里还没有 turnHeader（判定不了，别乱猜）。
         */
        fun turnRunning(): Boolean? {
            for (i in rows.length() - 1 downTo 0) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("kind") != "turnHeader") continue
                val state = row.optString("state")
                if (state.isEmpty()) return null
                return state == "running" || state == "pending"
            }
            return null
        }

        private fun applyRowDelta(op: JSONObject) {
            val rowId = op.optString("rowId")
            val at = indexOfRow(rowId)
            if (at < 0) return
            val row = rows.optJSONObject(at) ?: return
            val append = op.optString("append")
            if (append.isEmpty()) return
            when (op.optString("path")) {
                "text" -> if (row.optString("kind") == "assistantText" ||
                    row.optString("kind") == "reasoning"
                ) {
                    row.put("text", row.optString("text") + append)
                }
                "inputText" -> if (row.optString("kind") == "toolCall") {
                    row.put("inputText", row.optString("inputText") + append)
                }
                "summaryText" -> if (row.optString("kind") == "subagent") {
                    row.put("summaryText", row.optString("summaryText") + append)
                }
                "output.text" -> if (row.optString("kind") == "toolCall") {
                    val output = row.optJSONObject("output") ?: JSONObject()
                    output.put("text", output.optString("text") + append)
                    row.put("output", output)
                }
            }
        }

        private fun applyStatePatch(op: JSONObject) {
            // state.updated patches conversation-store metadata, not a row.
            // The tail only models rows, so there is deliberately nothing to mutate.
        }

        private fun indexOfRow(rowId: String): Int {
            if (rowId.isEmpty()) return -1
            for (i in 0 until rows.length()) {
                if (rows.optJSONObject(i)?.optString("rowId") == rowId) return i
            }
            return -1
        }

        private fun trim() {
            if (rows.length() <= TAIL_ROWS) return
            val kept = JSONArray()
            for (i in rows.length() - TAIL_ROWS until rows.length()) kept.put(rows.opt(i))
            rows = kept
        }

        private companion object {
            /** 只留这么多行：流体云要的是最新一行，往前都是浪费。 */
            const val TAIL_ROWS = 60
        }
    }
}
