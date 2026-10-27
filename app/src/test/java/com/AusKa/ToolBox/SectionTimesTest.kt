package com.AusKa.ToolBox

import com.AusKa.ToolBox.data.SectionTimesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 上课时间表的纯逻辑：默认值数量、时间格式校验 */
class SectionTimesTest {

    @Test
    fun defaultTablesHaveTwelveSections() {
        assertEquals(12, SectionTimesStore.SECTIONS)
        assertEquals(12, SectionTimesStore.defaultStarts.size)
        assertEquals(12, SectionTimesStore.defaultEnds.size)
        assertEquals("08:00", SectionTimesStore.defaultStarts.first())
        assertEquals("22:30", SectionTimesStore.defaultEnds.last())
    }

    @Test
    fun parseTimeAcceptsNormalInput() {
        assertEquals("08:00", SectionTimesStore.parseTime("8:00"))
        assertEquals("09:40", SectionTimesStore.parseTime(" 09:40 "))
        assertEquals("19:05", SectionTimesStore.parseTime("19：05"))
    }

    @Test
    fun parseTimeRejectsGarbage() {
        assertNull(SectionTimesStore.parseTime(""))
        assertNull(SectionTimesStore.parseTime("八点"))
        assertNull(SectionTimesStore.parseTime("25:00"))
        assertNull(SectionTimesStore.parseTime("08:70"))
    }
}
