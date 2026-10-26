package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.TaskScan
import com.AusKa.ToolBox.chaoxing.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaoxingTaskTest {

    @Test
    fun `扫到的任务归一化`() {
        val raw = """
            {"t":"tasks","count":3,"items":[
              {"course":"高等数学","title":"第三章 习题","status":"todo","url":"https://mooc1.chaoxing.com/work/1"},
              {"course":"大学英语","title":"Unit 2 作文","status":"done","url":"https://mooc1.chaoxing.com/work/2"},
              {"course":"","title":"期末考试","status":"expired","url":""}
            ]}
        """.trimIndent()
        val tasks = TaskScan.parse(raw)
        assertEquals(3, tasks.size)
        assertEquals("高等数学", tasks[0].course)
        assertEquals(TaskStatus.Todo, tasks[0].status)
        assertEquals("https://mooc1.chaoxing.com/work/1", tasks[0].url)
        assertEquals(TaskStatus.Done, tasks[1].status)
        // 没有课程名的兜底文案，不能出现空字符串
        assertEquals("未标注课程", tasks[2].course)
        assertEquals(TaskStatus.Expired, tasks[2].status)
    }

    @Test
    fun `没有标题的条目直接丢掉`() {
        val raw = """
            {"t":"tasks","items":[
              {"course":"物理","title":"   ","status":"todo","url":"u1"},
              {"course":"物理","title":"实验报告","status":"unknown","url":"u2"}
            ]}
        """.trimIndent()
        val tasks = TaskScan.parse(raw)
        assertEquals(1, tasks.size)
        assertEquals("实验报告", tasks[0].title)
        assertEquals(TaskStatus.Unknown, tasks[0].status)
    }

    @Test
    fun `认不出来的状态一律待确认`() {
        val raw = """
            {"t":"tasks","items":[{"course":"化学","title":"习题","status":"weird","url":""}]}
        """.trimIndent()
        assertEquals(TaskStatus.Unknown, TaskScan.parse(raw)[0].status)
    }

    @Test
    fun `空串与坏 JSON 都返回空列表`() {
        assertTrue(TaskScan.parse("").isEmpty())
        assertTrue(TaskScan.parse("not json").isEmpty())
        assertTrue(TaskScan.parse("[]").isEmpty())
        assertTrue(TaskScan.parse("""{"t":"error","message":"boom"}""").isEmpty())
    }

    @Test
    fun `统计只数待做和已截止`() {
        val raw = """
            {"t":"tasks","items":[
              {"course":"A","title":"1","status":"todo","url":""},
              {"course":"A","title":"2","status":"todo","url":""},
              {"course":"B","title":"3","status":"expired","url":""},
              {"course":"B","title":"4","status":"done","url":""}
            ]}
        """.trimIndent()
        val tasks = TaskScan.parse(raw)
        assertEquals(2, TaskScan.todoCount(tasks))
        assertEquals(1, TaskScan.expiredCount(tasks))
        assertEquals(2, TaskScan.courseCount(tasks))
    }

    @Test
    fun `超长标题会被截断`() {
        val title = "作".repeat(120)
        val raw = """{"t":"tasks","items":[{"course":"C","title":"$title","status":"todo","url":""}]}"""
        assertEquals(60, TaskScan.parse(raw)[0].title.length)
    }

    @Test
    fun `课程列表按课程班级去重`() {
        val raw = """
            {"t":"courses","items":[
              {"courseId":"100","classId":"20","course":"数据结构"},
              {"courseId":"100","classId":"20","course":"数据结构"},
              {"courseId":"101","classId":"21","course":"线性代数"},
              {"courseId":"","classId":"21","course":"没有课程号的"}
            ]}
        """.trimIndent()
        val courses = TaskScan.parseCourses(raw)
        assertEquals(2, courses.size)
        assertEquals("数据结构", courses[0].name)
        assertEquals("101", courses[1].courseId)
        assertEquals("21", courses[1].classId)
    }

    @Test
    fun `作业行带截止时间和考试标记`() {
        val raw = """
            {"t":"rows","items":[
              {"title":"第三章 课后作业","status":"todo","due":"2026-11-02 23:59","url":"u1","kind":"exam"},
              {"title":"期中考试","status":"expired","due":"","url":"u2","kind":"exam"}
            ]}
        """.trimIndent()
        val rows = TaskScan.parseRows(raw, "数据结构", true)
        assertEquals(2, rows.size)
        assertEquals("2026-11-02 23:59", rows[0].due)
        assertTrue(rows[0].exam)
        // 考试页扫出来的一律按考试算，即使 kind 没写
        assertTrue(rows[1].exam)
        assertEquals("数据结构", rows[0].course)
    }

    @Test
    fun `合并按 key 去重并保留后到的状态`() {
        val first = TaskScan.parseRows(
            """{"t":"rows","items":[{"title":"习题","status":"todo","due":"","url":"u1","kind":""}]}""",
            "A",
            false,
        )
        val second = TaskScan.parseRows(
            """{"t":"rows","items":[
                {"title":"习题","status":"done","due":"","url":"u1","kind":""},
                {"title":"新作业","status":"todo","due":"","url":"u2","kind":""}
            ]}""",
            "A",
            false,
        )
        val merged = TaskScan.merge(first, second)
        assertEquals(2, merged.size)
        assertEquals(TaskStatus.Done, merged.first { it.url == "u1" }.status)
    }

    @Test
    fun `待完成与已完成分桶`() {
        val tasks = TaskScan.parseRows(
            """{"t":"rows","items":[
                {"title":"1","status":"todo","due":"","url":"a","kind":""},
                {"title":"2","status":"done","due":"","url":"b","kind":""},
                {"title":"3","status":"expired","due":"","url":"c","kind":""}
            ]}""",
            "A",
            false,
        )
        assertEquals(2, TaskScan.open(tasks).size)
        assertEquals(1, TaskScan.done(tasks).size)
    }

    @Test
    fun `作业页与考试页地址带上课程班级`() {
        val course = com.AusKa.ToolBox.chaoxing.ScanCourse("100", "20", "数据结构")
        assertTrue(com.AusKa.ToolBox.chaoxing.ChaoxingUrls.workList(course).contains("courseId=100"))
        assertTrue(com.AusKa.ToolBox.chaoxing.ChaoxingUrls.workList(course).contains("classId=20"))
        assertTrue(com.AusKa.ToolBox.chaoxing.ChaoxingUrls.examList(course).contains("exam-list"))
    }
}
