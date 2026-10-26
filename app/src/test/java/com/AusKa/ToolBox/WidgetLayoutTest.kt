package com.AusKa.ToolBox

import com.AusKa.ToolBox.widget.RowStyle
import com.AusKa.ToolBox.widget.rowHeightDp
import com.AusKa.ToolBox.widget.widgetPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件排版：3×2 的卡片要塞下当天的课，靠的是「两行版 → 单行版 → 让标题 → 让日期」逐级降级。
 * 这套算法算错了，桌面上就会出现课被裁掉、或者明明能塞下却只显示两门。
 */
class WidgetLayoutTest {

    @Test
    fun `没课时不画行`() {
        val plan = widgetPlan(150, 0)
        assertEquals(0, plan.rows)
        assertEquals(RowStyle.TwoLine, plan.style)
        assertTrue(plan.showHeader)
        assertTrue(plan.showTitle)
    }

    @Test
    fun `两门课用两行版`() {
        // 150 的预算：两行版一行 47dp，两行 94dp 塞不进「日期 + 标题 + 两行」，
        // 但让掉标题就够了 —— 这就是默认 3×2 能显示两门完整课程的地方
        val plan = widgetPlan(150, 2)
        assertEquals(RowStyle.TwoLine, plan.style)
        assertEquals(2, plan.rows)
        assertTrue(plan.showHeader)
        assertFalse(plan.showTitle)
    }

    @Test
    fun `三门课压成单行版`() {
        val plan = widgetPlan(150, 3)
        assertEquals(RowStyle.OneLine, plan.style)
        assertEquals(3, plan.rows)
        assertTrue(plan.showHeader)
        assertTrue(plan.showTitle)
    }

    @Test
    fun `四门课让掉标题`() {
        val plan = widgetPlan(150, 4)
        assertEquals(RowStyle.OneLine, plan.style)
        assertEquals(4, plan.rows)
        assertTrue(plan.showHeader)
        assertFalse(plan.showTitle)
    }

    @Test
    fun `五门课连日期行也让掉`() {
        val plan = widgetPlan(150, 5)
        assertEquals(RowStyle.OneLine, plan.style)
        assertEquals(5, plan.rows)
        assertFalse(plan.showHeader)
        assertFalse(plan.showTitle)
    }

    @Test
    fun `拖高一格能全放下`() {
        val plan = widgetPlan(210, 6)
        assertEquals(6, plan.rows)
        assertTrue(plan.showTitle)
    }

    @Test
    fun `很高的卡片回到两行版`() {
        val plan = widgetPlan(290, 4)
        assertEquals(RowStyle.TwoLine, plan.style)
        assertEquals(4, plan.rows)
        assertTrue(plan.showTitle)
    }

    @Test
    fun `装不下时截断并提示`() {
        val plan = widgetPlan(150, 8)
        assertEquals(RowStyle.OneLine, plan.style)
        assertTrue(plan.rows in 1..7)
        assertFalse(plan.showTitle)
    }

    @Test
    fun `高度为 0 时至少留一行`() {
        assertEquals(1, widgetPlan(0, 8).rows)
    }

    @Test
    fun `大字号会把两行版压成单行版`() {
        assertEquals(47, rowHeightDp(RowStyle.TwoLine))
        assertEquals(24, rowHeightDp(RowStyle.OneLine))
        assertEquals(33, rowHeightDp(RowStyle.OneLine, 1.4f))
        val plan = widgetPlan(150, 4, 1.4f)
        assertEquals(RowStyle.OneLine, plan.style)
        assertTrue(plan.rows < 4)
    }
}
