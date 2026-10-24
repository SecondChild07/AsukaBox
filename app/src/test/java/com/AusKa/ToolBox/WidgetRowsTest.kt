package com.AusKa.ToolBox

import com.AusKa.ToolBox.widget.widgetRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** 小组件能放几行，按格子高度算 —— 算错了就会出现「第 3 行被裁一半」。 */
class WidgetRowsTest {

    @Test
    fun `最小高度至少一行`() {
        assertEquals(1, widgetRows(0))
        assertEquals(1, widgetRows(60))
        assertEquals(1, widgetRows(120))
    }

    @Test
    fun `随高度线性增长`() {
        // (190 - 84) / 42 = 2
        assertEquals(2, widgetRows(190))
        // (240 - 84) / 42 = 3
        assertEquals(3, widgetRows(240))
    }

    @Test
    fun `上限六行`() {
        assertEquals(6, widgetRows(2000))
        assertEquals(6, widgetRows(Int.MAX_VALUE))
    }
}
