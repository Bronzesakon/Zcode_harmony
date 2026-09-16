package com.zcode.remote.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * sessions-index 快照/增量的本地状态（Tier2 原生侧，M3c 数据面）。
 *
 * 逐条对照 zcode-protocol.js 的 SessionsIndexState（快照重建、`deltas` 增量、
 * `fromSeq !== seq` 缺口判重同步、logical frame 分片重组），输出的任务条目
 * 字段与壳原生侧 `ShellRuntime.onSessions` 的消费形状完全一致——
 * Tier2 接管期间的任务事件因此能直接进 TaskStore/通知链路。
 */
data class SessionEntry(
    val sessionId: String,
    val parentSessionId: String,
    val title: String,
    val phase: String,
    val preview: String,
    val lastActivityAt: Long,
    val hasBackgroundWork: Boolean,
    val pendingInteractionId: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sessionId", sessionId)
        .put("parentSessionId", parentSessionId)
        .put("title", title)
        .put("phase", phase)
        .put("preview", preview)
        .put("lastActivityAt", lastActivityAt)
        .put("hasBackgroundWork", hasBackgroundWork)
        .put("pendingInteractionId", pendingInteractionId)

    companion object {
        fun fromRaw(raw: JSONObject): SessionEntry {
            val pending = raw.optJSONObject("pendingInteraction")
            val interactionId = if (pending != null && pending.has("interactionId") &&
                !pending.isNull("interactionId")
            ) {
                pending.optString("interactionId")
            } else {
                ""
            }
            return SessionEntry(
                sessionId = raw.optString("sessionId", ""),
                parentSessionId = raw.optString("parentSessionId", ""),
                title = raw.optString("title", ""),
                phase = raw.optString("phase", ""),
                preview = raw.optString("lastAssistantPreview", ""),
                lastActivityAt = if (raw.has("lastActivityAt") && !raw.isNull("lastActivityAt")) {
                    raw.optLong("lastActivityAt")
                } else {
                    0L
                },
                hasBackgroundWork = raw.optBoolean("hasBackgroundWork", false),
                pendingInteractionId = interactionId,
            )
        }
    }
}

/**
 * 逻辑帧分片重组上限（与协议 `logicalFrameAssemblyMaxFragments` 一致）。
 */
private const val MAX_LOGICAL_FRAGMENTS = 1024

class SessionsIndexState {

    var logEpoch: String? = null
        private set
    var seq: Long = 0
        private set

    /** 缺口标记：调用方消费后应清零并发 resyncSessionsIndexV4。 */
    @Volatile
    var needsResync = false

    private val sessions = LinkedHashMap<String, SessionEntry>()
    private val fragments = HashMap<String, LogicalAssembly>()

    private class LogicalAssembly(val count: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
    }

    /** 入口：wire 信封 `{topic, kind:'complete'|'fragment', …}`。 */
    fun applyWire(wire: JSONObject?): Boolean {
        if (wire == null) return false
        return when (wire.optString("kind")) {
            "complete" -> applyLogical(wire.optJSONObject("frame"))
            "fragment" -> applyFragmentWire(wire)
            else -> false
        }
    }

    private fun applyFragmentWire(wire: JSONObject): Boolean {
        val id = wire.optString("logicalFrameId", "")
        val index = wire.optInt("fragmentIndex", -1)
        val count = wire.optInt("fragmentCount", -1)
        val dataBase64 = wire.optString("dataBase64", "")
        if (id.isEmpty() || index < 0 || count < 1 ||
            count > MAX_LOGICAL_FRAGMENTS || index >= count || dataBase64.isEmpty()
        ) {
            return false
        }
        val assembly = fragments[id]?.takeIf { it.count == count }
            ?: LogicalAssembly(count).also { fragments[id] = it }
        if (assembly.parts[index] == null) assembly.received += 1
        assembly.parts[index] = try {
            java.util.Base64.getDecoder().decode(dataBase64)
        } catch (e: Exception) {
            fragments.remove(id)
            return false
        }
        if (assembly.received != count) return false
        fragments.remove(id)
        val merged = assembly.parts.map { requireNotNull(it) { "missing fragment" } }
            .reduce { acc, part -> acc + part }
        val decoded = try {
            JSONObject(String(merged, Charsets.UTF_8))
        } catch (e: Exception) {
            return false
        }
        return applyLogical(decoded)
    }

    /** logical frame：`{toSeq, fromSeq?, payload:{kind:'snapshot'|'deltas', …}}`。 */
    fun applyLogical(frame: JSONObject?): Boolean {
        if (frame == null) return false
        val payload = frame.optJSONObject("payload") ?: return false
        val toSeq = if (frame.has("toSeq") && !frame.isNull("toSeq")) {
            frame.optLong("toSeq")
        } else {
            seq
        }
        when (payload.optString("kind")) {
            "snapshot" -> {
                val snapshot = payload.optJSONObject("snapshot") ?: return false
                logEpoch = if (snapshot.has("logEpoch") && !snapshot.isNull("logEpoch")) {
                    snapshot.optString("logEpoch")
                } else {
                    null
                }
                sessions.clear()
                val list = snapshot.optJSONArray("sessions") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val entry = SessionEntry.fromRaw(list.optJSONObject(i) ?: JSONObject())
                    if (entry.sessionId.isNotEmpty()) sessions[entry.sessionId] = entry
                }
                seq = toSeq
            }
            "deltas" -> {
                // 旧帧/重复帧先丢：网页端在比较 fromSeq 之前就 `if(toSeq<=seq) return`。
                // 少了这一步，乱序到达的旧帧会被判成"断档"，把 resync 打成风暴
                // （真机 pre.97：300ms 内 6-10 发并发 resyncSessionsIndexV4）。
                if (toSeq <= seq) return false
                val fromSeq = if (frame.has("fromSeq") && !frame.isNull("fromSeq")) {
                    frame.optLong("fromSeq")
                } else {
                    seq
                }
                if (fromSeq != seq) {
                    // 丢了一次更新：必须重同步，否则相位表漂移、完成事件丢失。
                    needsResync = true
                    return false
                }
                val deltas = payload.optJSONArray("deltas") ?: JSONArray()
                for (d in 0 until deltas.length()) {
                    val delta = deltas.optJSONObject(d) ?: continue
                    when (delta.optString("op")) {
                        "session.upserted" -> {
                            val raw = delta.optJSONObject("session") ?: continue
                            val upserted = SessionEntry.fromRaw(raw)
                            if (upserted.sessionId.isNotEmpty()) sessions[upserted.sessionId] = upserted
                        }
                        "session.removed" -> sessions.remove(delta.optString("sessionId"))
                    }
                }
                seq = toSeq
            }
            else -> return false
        }
        return true
    }

    /** 最近活动优先（与 JS list() 的排序一致）。 */
    fun listSorted(): List<SessionEntry> = sessions.values.sortedByDescending { it.lastActivityAt }

    /**
     * 壳原生侧消费形状（与注入层 `post('sessions', …)` 完全同构）。
     * `title` 规则同 JS workspaceTitle：label → 路径末段 → identity → key。
     */
    fun buildUpdate(
        key: String,
        scope: JSONObject,
        source: String,
    ): JSONObject {
        val label = scope.optString("label", "")
        val path = scope.optString("workspacePath", "")
        val identity = scope.optString("workspaceIdentity", "")
        val title = when {
            label.isNotEmpty() -> label
            path.isNotEmpty() -> {
                val parts = path.split('/', '\\').filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) parts.last() else path
            }
            identity.isNotEmpty() -> identity
            else -> key
        }
        val arr = JSONArray()
        for (entry in listSorted()) arr.put(entry.toJson())
        return JSONObject()
            .put("key", key)
            .put("title", title)
            .put("workspacePath", path)
            .put("workspaceIdentity", identity)
            .put("source", source)
            .put("sessions", arr)
    }
}
