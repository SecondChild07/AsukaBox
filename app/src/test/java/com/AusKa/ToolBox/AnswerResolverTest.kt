package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.AnswerResolver
import com.AusKa.ToolBox.chaoxing.QuizAnswer
import com.AusKa.ToolBox.chaoxing.QuizQuestion
import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerResolverTest {

    private fun q(
        kind: String,
        stem: String = "",
        options: List<String> = emptyList(),
        inputs: Int = 0,
    ) = QuizQuestion(
        index = 0,
        kind = kind,
        stem = stem,
        options = options,
        inputs = inputs,
        editor = false,
        audio = "",
    )

    @Test
    fun lettersPassThrough() {
        val question = q("choice", options = listOf("A. 甲", "B. 乙", "C. 丙"))
        assertEquals("B", AnswerResolver.letters(question, QuizAnswer(letters = "b")))
    }

    @Test
    fun multiLettersKeepOrder() {
        val question = q("multi", options = listOf("A. 甲", "B. 乙", "C. 丙", "D. 丁"))
        assertEquals("ACD", AnswerResolver.letters(question, QuizAnswer(letters = "dca")))
    }

    @Test
    fun judgeTextMapsToOption() {
        val question = q("judge", options = listOf("A. 对", "B. 错"))
        assertEquals("A", AnswerResolver.letters(question, QuizAnswer(texts = listOf("对"))))
        assertEquals("B", AnswerResolver.letters(question, QuizAnswer(texts = listOf("错误"))))
    }

    @Test
    fun judgeTrueFalseOptions() {
        val question = q("judge", options = listOf("A. 正确", "B. 错误"))
        assertEquals("B", AnswerResolver.letters(question, QuizAnswer(texts = listOf("×"))))
    }

    @Test
    fun choiceTextMatchesOptionContent() {
        val question = q(
            "choice",
            options = listOf("A. 《南京条约》", "B. 《马关条约》", "C. 《辛丑条约》"),
        )
        assertEquals("B", AnswerResolver.letters(question, QuizAnswer(texts = listOf("《马关条约》"))))
        assertEquals("C", AnswerResolver.letters(question, QuizAnswer(texts = listOf("辛丑条约"))))
    }

    @Test
    fun unmatchedTextGivesNoLetter() {
        val question = q("choice", options = listOf("A. 甲", "B. 乙"))
        assertEquals("", AnswerResolver.letters(question, QuizAnswer(texts = listOf("完全无关的内容"))))
    }

    @Test
    fun emptyAnswerGivesNothing() {
        val question = q("choice", options = listOf("A. 甲", "B. 乙"))
        assertEquals("", AnswerResolver.letters(question, QuizAnswer()))
        assertEquals(emptyList<String>(), AnswerResolver.texts(question, QuizAnswer()))
    }

    @Test
    fun fillPackedIntoOneStringIsSplit() {
        val question = q("fill", inputs = 3)
        val got = AnswerResolver.texts(question, QuizAnswer(texts = listOf("空气；水；土壤")))
        assertEquals(listOf("空气", "水", "土壤"), got)
    }

    @Test
    fun fillSingleSlotKeepsWholeSentence() {
        val question = q("fill", inputs = 1)
        val got = AnswerResolver.texts(question, QuizAnswer(texts = listOf("新民主主义革命")))
        assertEquals(listOf("新民主主义革命"), got)
    }

    @Test
    fun essayKeepsTextAsIs() {
        val question = q("essay", inputs = 1)
        val text = "第一，坚持党的领导。\n第二，依靠人民群众。"
        assertEquals(listOf(text), AnswerResolver.texts(question, QuizAnswer(texts = listOf(text))))
    }
}
