package com.zcode.remote.core

import org.json.JSONObject

/**
 * `controller/tasks-index` 的本地状态（Tier2 的**运行态**来源）。
 *
 * 为什么需要它：`sessions-index` 的 `phase` 是**持久化**的会话状态——一轮对话
 * 结束就写 `completedSuccess`，与"此刻是否在跑"无关。网页客户端的实时运行态来自
 * 另一条 controller 流，任务条目的 `liveStatus`（`idle|running|waiting|completed|
 * error`）才是正源（2026-09-14 从 bundle 定案，见 docs/05 的 `taskSchema` 与
 * `windowControllerTaskListRegistry`）。
 *
 * 真机后果：Tier2 从不订阅这条流，接管后 `sessions-index` 的快照把 store 里
 * 仅剩的运行态覆盖成"全完成"，`runningTaskRefs()` 变空 → 活进展被丢弃 →
 * 流体云卡片冻在接管那一刻（pre.96/97/98 连续复现）。
 *
 * 逐条对照 bundle 的 registry：
 *   * 订阅：`subscribeControllerV4({topic, visibility})` → `ack.subscriptionId`
 *   * 帧：`onDynamicControllerFrame` → `{topic, subscriptionId, logEpoch, fromSeq,
 *     toSeq, payload:{kind:'snapshot'|'deltas'}}`
 *   * snapshot：`payload.snapshot.tasks`（按 `address` 建键）
 *   * deltas：`task.upserted`（带 `task`）/ `task.removed`（带 `address`）
 *   * 缺口：`toSeq <= seq` 丢弃（旧帧/重复帧）；否则 `fromSeq != seq` ⇒ 重同步
 */
class ControllerTasksState {

    /** 一条任务对通知层有意义的投影。`phase` 已按 [mapLiveStatus] 归一。 */
    data class LiveTask(
        val workspaceKey: String,
        val sessionId: String,
        val title: String,
        val phase: String,
        val liveStatus: String,
    )

    var logEpoch: String? = null
        private set

    var seq: Long = 0
        private set

    var subscriptionId: String? = null
        private set

    /** 缺口标记：调用方消费后清零并发 `resyncControllerV4`。 */
    @Volatile
    var needsResync = false

    private val tasks = LinkedHashMap<String, JSONObject>()
    private val fragments = HashMap<String, Assembly>()

    private class Assembly(val count: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
    }

    fun resetState() {
        logEpoch = null
        seq = 0
        subscriptionId = null
        needsResync = false
        tasks.clear()
        fragments.clear()
    }

    fun bind(subId: String) {
        subscriptionId = subId
    }

    /** 入口：wire 信封 `{topic, kind:'complete'|'fragment', …}`。 */
    fun applyWire(wire: JSONObject?): Boolean {
        if (wire == null) return false
        return when (wire.optString("kind")) {
            "complete" -> applyLogical(wire.optJSONObject("frame"))
            "fragment" -> applyFragment(wire)
            else -> false
        }
    }

    private fun applyFragment(wire: JSONObject): Boolean {
        val id = wire.optString("logicalFrameId", "")
        val index = wire.optInt("fragmentIndex", -1)
        val count = wire.optInt("fragmentCount", -1)
        val data = wire.optString("dataBase64", "")
        if (id.isEmpty() || index < 0 || count < 1 || count > MAX_FRAGMENTS ||
            index >= count || data.isEmpty()
        ) {
            return false
        }
        val assembly = fragments[id]?.takeIf { it.count == count }
            ?: Assembly(count).also { fragments[id] = it }
        if (assembly.parts[index] == null) assembly.received += 1
        assembly.parts[index] = try {
            java.util.Base64.getDecoder().decode(data)
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

    private fun applyLogical(frame: JSONObject?): Boolean {
        if (frame == null) return false
        val payload = frame.optJSONObject("payload") ?: return false
        val kind = payload.optString("kind")
        val epoch = frame.optString("logEpoch").takeIf { it.isNotEmpty() }
        val toSeq = frame.optLong("toSeq", -1L)
        if (kind == "snapshot") {
            val snapshot = payload.optJSONObject("snapshot") ?: return false
            logEpoch = snapshot.optString("logEpoch").takeIf { it.isNotEmpty() } ?: epoch
            subscriptionId = frame.optString("subscriptionId").takeIf { it.isNotEmpty() }
                ?: subscriptionId
            tasks.clear()
            val list = snapshot.optJSONArray("tasks")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val task = list.optJSONObject(i) ?: continue
                    val key = addressKey(task.optJSONObject("address")) ?: continue
                    tasks[key] = task
                }
            }
            if (toSeq >= 0L) seq = toSeq
            return true
        }
        if (kind != "deltas") return false
        // 旧帧/重复帧：网页端在比较 fromSeq **之前**先丢弃 toSeq<=seq（bundle ZIe）。
        if (toSeq >= 0L && toSeq <= seq && logEpoch != null) return false
        // 没有基线（还没收到 snapshot）时的 deltas 一律不可信——网页端同样判 gap：
        // `if(!n||e.fromSeq!==n.seq)` 里 n 为空即走恢复路径。
        val fromSeq = frame.optLong("fromSeq", -1L)
        val subId = frame.optString("subscriptionId").takeIf { it.isNotEmpty() }
        if (logEpoch == null || (subId != null && subId != subscriptionId) ||
            (epoch != null && epoch != logEpoch) || fromSeq != seq
        ) {
            needsResync = true
            return false
        }
        val deltas = payload.optJSONArray("deltas") ?: return false
        for (i in 0 until deltas.length()) {
            val delta = deltas.optJSONObject(i) ?: continue
            when (delta.optString("op")) {
                "task.upserted" -> {
                    val task = delta.optJSONObject("task") ?: continue
                    val key = addressKey(task.optJSONObject("address")) ?: continue
                    tasks[key] = task
                }
                "task.removed" -> addressKey(delta.optJSONObject("address"))?.let { tasks.remove(it) }
            }
        }
        if (toSeq >= 0L) seq = toSeq
        return true
    }

    /** 任务的运行态投影（已映射成壳侧的 phase 词汇）。 */
    fun liveTasks(): List<LiveTask> {
        val out = ArrayList<LiveTask>(tasks.size)
        for (task in tasks.values) {
            val address = task.optJSONObject("address") ?: continue
            val workspaceKey = workspaceKeyOfAddress(address) ?: continue
            val sessionId = address.optString("taskId", "")
            if (sessionId.isEmpty()) continue
            val meta = task.optJSONObject("meta") ?: JSONObject()
            val liveStatus = task.optString("liveStatus", "")
            out.add(
                LiveTask(
                    workspaceKey = workspaceKey,
                    sessionId = sessionId,
                    title = meta.optString("title", ""),
                    phase = mapLiveStatus(liveStatus),
                    liveStatus = liveStatus,
                )
            )
        }
        return out
    }

    private companion object {
        /** 与协议的 `logicalFrameAssemblyMaxFragments` 一致（不是物理帧的 64）。 */
        const val MAX_FRAGMENTS = 1024

        /** `address` 的键：`[workspaceIdentity ?: workspacePath, taskId]`。 */
        fun addressKey(address: JSONObject?): String? {
            if (address == null) return null
            val key = workspaceKeyOfAddress(address) ?: return null
            val taskId = address.optString("taskId", "")
            if (taskId.isEmpty()) return null
            return "$key|$taskId"
        }

        fun workspaceKeyOfAddress(address: JSONObject): String? {
            val identity = address.optString("workspaceIdentity", "")
            if (identity.isNotEmpty()) return identity
            val path = address.optString("workspacePath", "")
            if (path.isNotEmpty()) return path
            return null
        }

        /**
         * `liveStatus` → 壳侧的 phase 词汇（[NotifyState]）。
         *
         * `waiting` 归 `running`：它确实在跑，只是卡在等用户确认，卡片状态词由
         * `pendingInteraction` 呈现为「等待确认」。`idle` 归终态：一轮结束
         * （与 sessions-index 写 completedSuccess 的时机一致），这样"跑完"能触发
         * 完成卡片，而不是让卡片无声消失。
         */
        fun mapLiveStatus(liveStatus: String): String = when (liveStatus) {
            "running", "prewarming", "waiting" -> "running"
            "completed", "idle" -> "completedSuccess"
            "error" -> "failed"
            else -> ""
        }
    }
}
