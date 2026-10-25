package com.AusKa.ToolBox

import com.AusKa.ToolBox.data.TermCalendar
import com.AusKa.ToolBox.data.Timetable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 同步/导入一份新课表时，开学日期该不该沿用旧的。
 *
 * 这块栽过一次：修「别冲掉用户校准的开学日期」时只判断了「旧的那份有没有日期」，
 * 没判断「是不是同一个学期」，于是换学期后新课表继承了上学期的开学日，
 * `currentWeek` 从旧日期算起 → 20 多周 → clamp 到末尾 → 界面永远停在最后一周。
 */
class TermCalendarTest {

    /** 2025-2026 秋季，开学日期被用户校准过 */
    private val autumn = Timetable(
        courses = emptyList(),
        year = 2025,
        term = 3,
        startDate = "2025-09-01",
    )

    @Test
    fun `同一学期沿用校准过的开学日期`() {
        val next = autumn.copy(startDate = "")
        assertEquals("2025-09-01", TermCalendar.startDateFor(next, autumn))
    }

    @Test
    fun `换学期不继承上一学期的开学日期`() {
        val spring = autumn.copy(year = 2025, term = 12)
        val got = TermCalendar.startDateFor(spring, autumn)

        assertEquals(TermCalendar.format(TermCalendar.estimateStart(2025, 12)), got)
        assertNotEquals(autumn.startDate, got)
    }

    @Test
    fun `旧课表开学日期为空时按学期估算`() {
        val next = autumn.copy(startDate = "")
        assertEquals(
            TermCalendar.format(TermCalendar.estimateStart(2025, 3)),
            TermCalendar.startDateFor(next, autumn.copy(startDate = "")),
        )
    }

    @Test
    fun `第一次导入没有旧课表时按学期估算`() {
        assertEquals(
            TermCalendar.format(TermCalendar.estimateStart(2025, 3)),
            TermCalendar.startDateFor(autumn.copy(startDate = ""), null),
        )
    }
}
