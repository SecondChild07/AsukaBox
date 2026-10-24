package com.AusKa.ToolBox

import com.AusKa.ToolBox.data.Course
import org.junit.Assert.assertEquals
import org.junit.Test

/** `zcd` 周次串的解析 —— 这块早期栽过跟头（单双周被当成整串标志，导致第 9 周之后某门课消失）。 */
class CourseWeeksTest {

    @Test
    fun `普通区间`() {
        assertEquals((1..16).toSet(), Course.parseWeeks("1-16周"))
    }

    @Test
    fun `跳周`() {
        assertEquals((1..8).toSet() + (10..16).toSet(), Course.parseWeeks("1-8,10-16周"))
    }

    @Test
    fun `单双周是段级的`() {
        // 只有 6-8 是双周，9-18 每周都有 —— 这是当初出 bug 的那串
        val weeks = Course.parseWeeks("1-3周,6-8周(双),9-18周")
        assertEquals(setOf(1, 2, 3, 6, 8) + (9..18).toSet(), weeks)
    }

    @Test
    fun `单周`() {
        assertEquals(setOf(1, 3, 5, 7), Course.parseWeeks("1-8周(单)"))
    }

    @Test
    fun `单个周次`() {
        assertEquals(setOf(16), Course.parseWeeks("第16周"))
    }

    @Test
    fun `中文逗号与分号`() {
        assertEquals(setOf(1, 2, 5, 6), Course.parseWeeks("1-2周，5-6周"))
        assertEquals(setOf(1, 2, 5, 6), Course.parseWeeks("1-2周;5-6周"))
    }

    @Test
    fun `空串与垃圾输入不崩`() {
        assertEquals(emptySet<Int>(), Course.parseWeeks(""))
        assertEquals(emptySet<Int>(), Course.parseWeeks("周"))
        assertEquals(emptySet<Int>(), Course.parseWeeks("(双)"))
    }

    @Test
    fun `节次区间`() {
        assertEquals(1 to 2, Course.parseSections("1-2"))
        assertEquals(5 to 5, Course.parseSections("5"))
        assertEquals(3 to 4, Course.parseSections("3-4节"))
    }

    // —— 审计清单 P1-11 的三条边界 ——

    @Test
    fun `空周次串当每周都有`() {
        val c = Course("x", "", "", 1, 1, 2, Course.parseWeeks(""))
        // 解析不出来时不能把课整门吃掉：空集 = 每周都有
        assertEquals(true, c.inWeek(1))
        assertEquals(true, c.inWeek(18))
    }

    @Test
    fun `四位数紧凑节次`() {
        // 部分部署用 0506 表示第 5-6 节
        assertEquals(5 to 6, Course.parseSections("0506"))
        assertEquals(1 to 2, Course.parseSections("0102"))
        assertEquals(5 to 6, Course.parseSections("5-6"))
    }

    @Test
    fun `周次上限按学期周数而不是写死 16`() {
        val courses = listOf(Course("x", "", "", 1, 1, 2, setOf(1, 2)))
        val t = com.AusKa.ToolBox.data.Timetable(courses, 2026, 3, weeksInTerm = 18)
        assertEquals(18, t.maxWeek)
        val t20 = com.AusKa.ToolBox.data.Timetable(courses, 2026, 3)
        assertEquals(20, t20.maxWeek)
    }
}
