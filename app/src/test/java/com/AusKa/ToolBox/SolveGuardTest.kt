package com.AusKa.ToolBox.chaoxing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作答令牌：换作业时旧的那条异步链必须自己退场。
 *
 * 这套判断错了会出两种事故 —— 要么旧答案填进新作业（令牌该作废却还活着），
 * 要么同一页跳个锚点就自己停掉（令牌该活着却被判死）。两头都得测。
 */
class SolveGuardTest {

    private val homeworkA = "https://mooc1.chaoxing.com/mooc-ans/work/doHomeWorkNew?courseId=1&workId=100"
    private val homeworkB = "https://mooc1.chaoxing.com/mooc-ans/work/doHomeWorkNew?courseId=1&workId=200"

    @Test
    fun `开跑之后这一轮是活的`() {
        val guard = SolveGuard()
        val run = guard.start(homeworkA)
        assertTrue(guard.alive(run))
        assertTrue(guard.aliveOn(run, homeworkA))
    }

    @Test
    fun `换作业的时候旧的一轮立刻作废`() {
        val guard = SolveGuard()
        val old = guard.start(homeworkA)
        val next = guard.start(homeworkB)
        assertFalse(guard.alive(old))
        assertTrue(guard.alive(next))
        // 旧链算完还会回到主线程：这时候既不是这一轮，页面也不是那一页
        assertFalse(guard.aliveOn(old, homeworkB))
    }

    @Test
    fun `停止之后所有在飞的回调都作废`() {
        val guard = SolveGuard()
        val run = guard.start(homeworkA)
        guard.stop()
        assertFalse(guard.alive(run))
        assertFalse(guard.aliveOn(run, homeworkA))
        // 停完不留地址：下一轮从干净状态开始
        assertEquals("", guard.started)
        assertEquals("", guard.page)
    }

    @Test
    fun `同一页跳锚点不算换作业`() {
        val guard = SolveGuard()
        val run = guard.start(homeworkA)
        assertTrue(guard.aliveOn(run, homeworkA + "#question-3"))
        assertTrue(guard.aliveOn(run, homeworkA + "/"))
        assertTrue(guard.aliveOn(run, homeworkA.uppercase()))
        assertTrue(guard.aliveOn(run, "  " + homeworkA + "  "))
    }

    @Test
    fun `题目翻页换了地址就得停`() {
        val guard = SolveGuard()
        val run = guard.start(homeworkA + "&page=1")
        assertFalse(guard.aliveOn(run, homeworkA + "&page=2"))
    }

    @Test
    fun `地址为空时不做页面判断`() {
        // 手动开跑时 webView.url 偶尔还是空串，这时候不能因为「地址对不上」就整轮停掉
        val guard = SolveGuard()
        val run = guard.start("")
        assertTrue(guard.stillOn(homeworkB))
        assertTrue(guard.aliveOn(run, homeworkB))
    }

    @Test
    fun `地址归一化忽略锚点斜杠和大小写`() {
        assertEquals(
            "https://mooc1.chaoxing.com/a?b=1",
            SolveGuard.key("HTTPS://mooc1.chaoxing.com/a?b=1/#top"),
        )
    }
}
