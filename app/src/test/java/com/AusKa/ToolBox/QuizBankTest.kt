package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.QuizBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 题库只测纯逻辑：题干归一化和在线返回解析（不碰 Context）。 */
class QuizBankTest {

    @Test
    fun normalizeDropsNumberingAndType() {
        val raw = "1. (单选题) 新民主主义革命的性质是( )。"
        assertEquals("新民主主义革命的性质是", QuizBank.normalize(raw))
    }

    @Test
    fun normalizeKeepsMeaningWhenNoiseRemoved() {
        val a = QuizBank.normalize("2、下列哪项属于 A. 甲 B. 乙")
        val b = QuizBank.normalize("下列哪项属于甲 乙")
        assertEquals(b, a)
    }

    @Test
    fun normalizeIsCaseInsensitiveForLetters() {
        assertEquals(QuizBank.normalize("True or False"), QuizBank.normalize("true  or  false"))
    }

    @Test
    fun onlineAnswerFromPlainField() {
        assertEquals("C", QuizBank.parseOnlineAnswer("{\"answer\":\"C\"}"))
    }

    @Test
    fun onlineAnswerFromNestedData() {
        assertEquals("AB", QuizBank.parseOnlineAnswer("{\"code\":0,\"data\":{\"answer\":\"AB\"}}"))
    }

    @Test
    fun onlineAnswerIgnoresHtml() {
        assertNull(QuizBank.parseOnlineAnswer("<html><body>请先登录</body></html>"))
    }
}
