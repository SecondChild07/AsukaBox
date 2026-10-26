package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.TaskStatus
import com.AusKa.ToolBox.chaoxing.WorkHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用线上真实抓下来的作业列表页当夹具（`src/test/resources/work-list*.html`）。
 *
 * 这些 HTML 是 2026-10 用真账号从 `mooc1.chaoxing.com/mooc2/work/list` 原生拉下来的，
 * 结构就是 `<li data="任务地址" aria-label="标题 ; 状态">` + `p.overHidden2` + `p.status`。
 */
class WorkHtmlTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `真实作业列表能解析出多行`() {
        val rows = WorkHtml.parse(fixture("work-list.html"), "大学英语", false)
        assertTrue("应至少解析出 10 行，实际 " + rows.size, rows.size >= 10)
        assertTrue(rows.all { it.url.startsWith("https://") })
        assertTrue(rows.all { it.course == "大学英语" })
    }

    @Test
    fun `已完成与未交分开归类`() {
        val done = WorkHtml.parse(fixture("work-list.html"), "大学英语", false)
        assertTrue(done.any { it.status == TaskStatus.Done })
        assertFalse(done.any { it.title.isBlank() })

        val mixed = WorkHtml.parse(fixture("work-list-todo.html"), "某课", false)
        assertTrue("混装页应有待完成行", mixed.any { it.status == TaskStatus.Todo })
        assertTrue("混装页应有已完成行", mixed.any { it.status == TaskStatus.Done })
    }

    @Test
    fun `待批阅算已完成`() {
        val html = """
            <div class="bottomList"><ul>
            <li data="https://mooc1.chaoxing.com/mooc2/work/task?workId=1" aria-label="小测 ; 待批阅">
              <p class="overHidden2 fl">小测</p><p class="status fl">待批阅</p></li>
            </ul></div>
        """.trimIndent()
        val rows = WorkHtml.parse(html, "某课", false)
        assertEquals(1, rows.size)
        assertEquals(TaskStatus.Done, rows[0].status)
        assertEquals("小测", rows[0].title)
    }

    @Test
    fun `待互评也算已完成 未交算待完成`() {
        val html = """
            <ul>
              <li data="https://mooc1.chaoxing.com/mooc2/work/task?workId=2">
                <p class="overHidden2 fl">互评作业</p><p class="status fl">待互评</p></li>
              <li data="https://mooc1.chaoxing.com/mooc2/work/task?workId=3">
                <p class="overHidden2 fl">实验报告</p><p class="status fl">未交</p></li>
            </ul>
        """.trimIndent()
        val rows = WorkHtml.parse(html, "某课", false)
        assertEquals(2, rows.size)
        assertEquals(TaskStatus.Done, rows[0].status)
        assertEquals(TaskStatus.Todo, rows[1].status)
    }

    @Test
    fun `没有任务时空列表`() {
        val rows = WorkHtml.parse("""<div class="null-data">暂无作业</div>""", "某课", false)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `被踢回登录页要能认出来`() {
        assertTrue(WorkHtml.looksLikeLogin("<html><head><title>用户登录</title></head></html>"))
        assertFalse(WorkHtml.looksLikeLogin(fixture("work-list.html")))
    }

    @Test
    fun `考试页标题带考试标记`() {
        val html = """
            <ul>
              <li data="https://mooc1.chaoxing.com/exam-ans/mooc2/exam/preview?examId=9">
                <p class="overHidden2 fl">期中考试</p><p class="status fl">未交</p></li>
            </ul>
        """.trimIndent()
        val rows = WorkHtml.parse(html, "高数", true)
        assertEquals(1, rows.size)
        assertTrue(rows[0].exam)
        assertEquals("期中考试", rows[0].title)
    }

    @Test
    fun `没有考试的考试页不能凭空造行`() {
        val rows = WorkHtml.parse(fixture("exam-list.html"), "物联网技术概论", true)
        assertTrue(
            "考试页里只有「暂无考试」，不该出任务行：" + rows.map { it.title },
            rows.isEmpty(),
        )
    }
}
