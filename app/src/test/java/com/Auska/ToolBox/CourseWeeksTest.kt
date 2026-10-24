package com.Auska.ToolBox

import com.Auska.ToolBox.data.Course
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
}
