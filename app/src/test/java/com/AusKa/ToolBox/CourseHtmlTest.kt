package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.CourseHtml
import com.AusKa.ToolBox.chaoxing.ScanCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseHtmlTest {

    private val page = """
        <html><body>
        <input type="hidden" id="showAIEnc" value="9319fd39a63743c4bc7076f255c440c9">
        <input type="hidden" id="enc" name="enc" value="7725c5f618ba864949a93ae1c33cbcd2">
        <input type="hidden" id="oldenc" name="oldenc" value="2f0111776cc338d8cc610c646b438f37">
        <input type="hidden" id="workEnc" name="workEnc" value="16afecc5ac9fdbe00eed4ead122181cd">
        <input type="hidden" id="examEnc" name="examEnc" value="2f8daffaf4763d58d343b089bddc7dc1">
        <input type="hidden" id="openc" name="openc" value="747c9f9970f5aca223533fc7bdf0329d">
        </body></html>
    """.trimIndent()

    private val course = ScanCourse("254762472", "131146500", "新时代大学英语（三）", "488832704")

    @Test
    fun `从课程页里读出四个签名参数`() {
        val keys = CourseHtml.keys(page)!!
        assertEquals("7725c5f618ba864949a93ae1c33cbcd2", keys.enc)
        assertEquals("16afecc5ac9fdbe00eed4ead122181cd", keys.workEnc)
        assertEquals("2f8daffaf4763d58d343b089bddc7dc1", keys.examEnc)
        assertEquals("747c9f9970f5aca223533fc7bdf0329d", keys.openc)
    }

    @Test
    fun `examEnc 不能顶掉 enc`() {
        val keys = CourseHtml.keys(page)!!
        assertTrue(keys.enc != keys.examEnc)
        assertEquals("7725c5f618ba864949a93ae1c33cbcd2", keys.enc)
    }

    @Test
    fun `缺 workEnc 就当这门课读不到`() {
        val broken = page.replace("<input type=\"hidden\" id=\"workEnc\" name=\"workEnc\" value=\"16afecc5ac9fdbe00eed4ead122181cd\">", "")
        assertNull(CourseHtml.keys(broken))
    }

    @Test
    fun `空串和登录页都读不到签名`() {
        assertNull(CourseHtml.keys(""))
        assertNull(CourseHtml.keys("<html><title>用户登录</title><input name=\"uname\"></html>"))
    }

    @Test
    fun `作业地址带齐 courseId classId cpi 和两份签名`() {
        val url = CourseHtml.workUrl(course, CourseHtml.keys(page)!!, "1793017045179")
        assertTrue(url.startsWith("https://mooc1.chaoxing.com/mooc2/work/list?"))
        assertTrue(url.contains("courseId=254762472"))
        assertTrue(url.contains("classId=131146500"))
        assertTrue(url.contains("cpi=488832704"))
        assertTrue(url.contains("t=1793017045179"))
        assertTrue(url.contains("stuenc=7725c5f618ba864949a93ae1c33cbcd2"))
        assertTrue(url.contains("enc=16afecc5ac9fdbe00eed4ead122181cd"))
    }

    @Test
    fun `考试地址用小写参数名并带上 openc`() {
        val url = CourseHtml.examUrl(course, CourseHtml.keys(page)!!, "1793017045179")
        assertTrue(url.startsWith("https://mooc1.chaoxing.com/exam-ans/mooc2/exam/exam-list?"))
        assertTrue(url.contains("courseid=254762472"))
        assertTrue(url.contains("clazzid=131146500"))
        assertTrue(url.contains("enc=2f8daffaf4763d58d343b089bddc7dc1"))
        assertTrue(url.contains("openc=747c9f9970f5aca223533fc7bdf0329d"))
    }

    @Test
    fun `没有 cpi 的课地址里不出现空参数`() {
        val plain = ScanCourse("1", "2", "某课")
        val url = CourseHtml.workUrl(plain, CourseHtml.keys(page)!!, "9")
        assertTrue(!url.contains("cpi="))
    }

    @Test
    fun `会话过期被踢回登录页要能认出来`() {
        assertTrue(CourseHtml.looksLikeLogin("<html><head><title>用户登录</title></head></html>"))
        assertTrue(CourseHtml.looksLikeLogin("<form><input name=\"uname\"><input name=\"password\"></form>"))
    }

    @Test
    fun `正常列表页不会被误判成登录页`() {
        val html = "<html><body>" + "作业".repeat(2000) + "</body></html>"
        assertTrue(!CourseHtml.looksLikeLogin(html))
        assertTrue(!CourseHtml.looksLikeLogin(""))
    }
}
