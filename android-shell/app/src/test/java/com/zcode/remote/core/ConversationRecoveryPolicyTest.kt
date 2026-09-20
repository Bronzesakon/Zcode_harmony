package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档 3 限流判据的语义钉子。
 *
 * 为什么这些用例值得存在：这套闸门已经**两次**因为语义写错而在真机上失效——
 * ① 清零挂错了信号（挂在"配对成功"而不是"用户回到对话"）⟹ 计数刚清零就又能恢复，
 * 限流形同虚设；② 清零只挂在回前台判据窗 ⟹ "恢复成功→用户就在对话里读→没切前后台
 * →进程被杀→回前台"这个真实序列被陈旧闸门挡下，用户白等。
 * 两条都在真机上花了一轮才看清，而真机复现"连续失败"要构造特定时序、很不可靠。
 * 所以语义钉在这里（同 [CarrierHandbackTest] 的做法）。
 */
class ConversationRecoveryPolicyTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `从未恢复过时放行`() {
        val d = ConversationRecoveryPolicy.decide(ConversationRecoveryPolicy.Record.EMPTY, t0)
        assertTrue("第一次没有理由拦", d.allowed)
    }

    @Test
    fun `五分钟后放行（间隔到期）`() {
        val rec = ConversationRecoveryPolicy.Record(
            count = 1,
            at = t0 - ConversationRecoveryPolicy.MIN_GAP_MS - 1,
        )
        assertTrue(
            "距上次已超过 5 分钟，应当放行",
            ConversationRecoveryPolicy.decide(rec, t0).allowed,
        )
    }

    @Test
    fun `间隔未到五分钟时拦下，且说明还剩多久`() {
        val rec = ConversationRecoveryPolicy.Record(count = 1, at = t0 - 246_000L)
        val d = ConversationRecoveryPolicy.decide(rec, t0)
        assertFalse("246s < 5 分钟，必须拦（真机 16:43 那次就是这个数）", d.allowed)
        assertTrue("日志要说清原因：${d.reason}", d.reason.contains("246s"))
    }

    @Test
    fun `同一毫秒内的第二次必拦（防连刷）`() {
        val rec = ConversationRecoveryPolicy.Record(count = 1, at = t0)
        assertFalse(
            "gap=0 必须拦——否则「每次回前台闪一次」",
            ConversationRecoveryPolicy.decide(rec, t0).allowed,
        )
    }

    @Test
    fun `到达上限后彻底停手（与间隔无关）`() {
        val rec = ConversationRecoveryPolicy.Record(
            count = ConversationRecoveryPolicy.CAP,
            at = t0 - ConversationRecoveryPolicy.MIN_GAP_MS * 10,
        )
        val d = ConversationRecoveryPolicy.decide(rec, t0)
        assertFalse("到顶之后即便间隔早过了也必须停手", d.allowed)
        assertTrue("日志要说清是「到顶」而不是「限流」：${d.reason}", d.reason.contains("上限"))
    }

    @Test
    fun `记一次恢复后次数加一、时刻刷新`() {
        val after = ConversationRecoveryPolicy.afterRecovery(ConversationRecoveryPolicy.Record.EMPTY, t0)
        assertEquals(1, after.count)
        assertEquals(t0, after.at)
        val twice = ConversationRecoveryPolicy.afterRecovery(after, t0 + 1_000L)
        assertEquals("符号名是 afterRecovery、不是 afterSuccess——清零是另一件事", 2, twice.count)
    }

    @Test
    fun `编解码往返，且解析失败按从未恢复处理`() {
        val rec = ConversationRecoveryPolicy.Record(count = 1, at = t0)
        assertEquals(rec, ConversationRecoveryPolicy.decode(ConversationRecoveryPolicy.encode(rec)))

        assertEquals(
            "读不到就放行一次：卡死不动比多恢复一次更糟，且次数上限还有兜底",
            ConversationRecoveryPolicy.Record.EMPTY,
            ConversationRecoveryPolicy.decode("{ 这不是 JSON"),
        )
        assertEquals(
            ConversationRecoveryPolicy.Record.EMPTY,
            ConversationRecoveryPolicy.decode(null),
        )
    }

    @Test
    fun `时钟回拨不会把闸门永久焊死`() {
        // gap 为负（时钟往回跳）时不拦：否则要等到时钟追上来才能恢复，等于永久焊死。
        val rec = ConversationRecoveryPolicy.Record(count = 1, at = t0 + 60_000L)
        assertTrue(
            "负 gap（时钟回拨）必须放行——焊死比多恢复一次更糟",
            ConversationRecoveryPolicy.decide(rec, t0).allowed,
        )
    }

    @Test
    fun `上限是两次——与注入层 KICKED 自愈同口径`() {
        assertEquals(
            "改这个数要同时回答：注入层 KICKED_HEAL_CAP 要不要一起改",
            2,
            ConversationRecoveryPolicy.CAP,
        )
    }
}
