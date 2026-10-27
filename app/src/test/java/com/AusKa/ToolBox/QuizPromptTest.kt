package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.QuizPrompt
import com.AusKa.ToolBox.chaoxing.QuizQuestion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提示词是准确率的主要杠杆，这几条断言防止以后改着改着把它写回「只给题干」 */
class QuizPromptTest {

    private fun q(kind: String, stem: String, options: List<String> = emptyList(), inputs: Int = 0) =
        QuizQuestion(0, kind, stem, options, inputs, false, "")

    @Test
    fun systemForcesSingleJsonObject() {
        val s = QuizPrompt.SYSTEM
        // 允许先给一句依据，但正文只有一个 JSON 对象
        assertTrue(s.contains("只输出一个 JSON"))
        assertTrue(s.contains("另起一行"))
        assertTrue(s.contains("不要解释"))
        // 不确定要留空，而不是瞎猜
        assertTrue(s.contains("conf"))
        // 六种格式都在
        listOf("\"answer\":\"C\",\"conf\":0.92", "\"pairs\"", "\"answer\":[\"").forEach {
            assertTrue("缺少格式样例：$it", s.contains(it))
        }
    }

    @Test
    fun choiceRuleTalksAboutWrongDirectionQuestions() {
        val rule = QuizPrompt.rule("choice")
        assertTrue(rule.contains("反向问法"))
        assertTrue(rule.contains("逐项判断"))
    }

    @Test
    fun multiRuleRequiresPerOptionCheck() {
        assertTrue(QuizPrompt.rule("multi").contains("每个选项单独判断"))
    }

    @Test
    fun fillRuleAsksForOneStringPerBlank() {
        val u = QuizPrompt.user(q("fill", "水的化学式是____", inputs = 2))
        assertTrue(u.contains("2 个空"))
        assertTrue(u.contains("按顺序"))
    }

    @Test
    fun userCarriesKindRuleStemAndOptions() {
        val u = QuizPrompt.user(
            q("choice", "马关条约签订于哪一年", listOf("A.1894", "B.1895", "C.1901")),
            course = "中国近现代史纲要",
        )
        assertTrue(u.contains("课程：中国近现代史纲要"))
        assertTrue(u.contains("题型：单选题"))
        assertTrue(u.contains("题干：马关条约签订于哪一年"))
        assertTrue(u.contains("A. A.1894") || u.contains("A. 1894") || u.contains("A."))
        assertTrue(u.contains("只输出 JSON"))
    }

    @Test
    fun transcriptIsIncludedForListening() {
        val u = QuizPrompt.user(q("listening", "What is the man going to do?"), "", "He will buy a ticket.")
        assertTrue(u.contains("听力原文"))
        assertTrue(u.contains("He will buy a ticket."))
        assertTrue(u.contains("听力题"))
    }

    @Test
    fun unknownKindStillGetsInstruction() {
        val rule = QuizPrompt.rule("weird")
        assertTrue(rule.contains("不确定就留空"))
        assertFalse(QuizPrompt.label("weird").isBlank())
    }
}
