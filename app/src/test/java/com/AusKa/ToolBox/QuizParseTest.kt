package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.AiParse
import com.AusKa.ToolBox.chaoxing.QuizParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 题目抽取结果与模型回答的解析：这两步最容易因为模型多嘴/页面少字段而静默出错 */
class QuizParseTest {

    private val sample = """
        {"t":"questions","count":2,"items":[
          {"i":0,"kind":"choice","stem":"1. (单选题)将中国领土台湾割让给日本的不平等条约是( )",
           "options":["A.《南京条约》","B.《北京条约》","C.《马关条约》"],
           "inputs":0,"editor":false,"audio":""},
          {"i":1,"kind":"essay","stem":"1. (论述题)撰写创业计划书",
           "options":[],"inputs":0,"editor":true,"audio":""}
        ]}
    """.trimIndent()

    @Test
    fun parsesQuestions() {
        val list = QuizParse.questions(sample)
        assertEquals(2, list.size)
        assertEquals("choice", list[0].kind)
        assertEquals(3, list[0].options.size)
        assertEquals("A.《南京条约》", list[0].options[0])
        assertEquals("essay", list[1].kind)
        assertTrue(list[1].editor)
    }

    @Test
    fun emptyAndGarbageGiveEmptyList() {
        assertTrue(QuizParse.questions("").isEmpty())
        assertTrue(QuizParse.questions("null").isEmpty())
        assertTrue(QuizParse.questions("{\"t\":\"probe\"}").isEmpty())
    }

    @Test
    fun letterAnswerGoesToLetters() {
        val a = AiParse.answer("{\"answer\":\"C\"}")
        assertEquals("C", a.letters)
        // 纯字母也留一份文本：填空题没有选项，模型可能就回一个单词
        assertEquals(listOf("C"), a.texts)
    }

    @Test
    fun multiLettersStayTogether() {
        val a = AiParse.answer("{\"answer\":\"acd\"}")
        assertEquals("ACD", a.letters)
    }

    @Test
    fun chineseAnswerGoesToTexts() {
        val a = AiParse.answer("{\"answer\":\"对\"}")
        assertEquals("", a.letters)
        assertEquals(listOf("对"), a.texts)
    }

    @Test
    fun arrayAnswerFillsBlanks() {
        val a = AiParse.answer("{\"answer\":[\"光合作用\",\"叶绿体\"]}")
        assertEquals(listOf("光合作用", "叶绿体"), a.texts)
    }

    @Test
    fun codeFenceIsTolerated() {
        val a = AiParse.answer("```json\n{\"answer\":\"B\"}\n```")
        assertEquals("B", a.letters)
    }

    @Test
    fun pairsBecomeLeftEqualsRight() {
        val a = AiParse.answer("{\"pairs\":[{\"left\":\"秦\",\"right\":\"公元前221年\"}]}")
        assertEquals(listOf("秦=公元前221年"), a.texts)
    }

    @Test
    fun junkGivesEmptyAnswer() {
        val a = AiParse.answer("我不知道这题选什么")
        assertEquals("", a.letters)
        assertTrue(a.texts.isEmpty())
    }

    @Test
    fun reasoningLineBeforeJsonStillParses() {
        // 提示词允许先写一句判断依据再给 JSON，解析必须取最后那个对象
        val raw = "1951 年西藏和平解放，与题干时间一致。\n{\"answer\":\"B\"}"
        assertEquals("B", AiParse.answer(raw).letters)
    }

    @Test
    fun lastJsonWinsWhenModelShowsFormatExamples() {
        val raw = "样例格式 {\"answer\":\"A\"} 实际答案：\n```json\n{\"answer\":\"D\"}\n```"
        assertEquals("D", AiParse.answer(raw).letters)
    }
}
