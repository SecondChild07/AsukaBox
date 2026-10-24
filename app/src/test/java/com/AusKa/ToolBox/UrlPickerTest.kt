package com.AusKa.ToolBox

import com.AusKa.ToolBox.nfc.UrlPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NFC 标签里那一坨原始文本 → 挑出最像签到链接的一条。 */
class UrlPickerTest {

    @Test
    fun `签到链接比普通链接分高`() {
        val checkin = UrlPicker.score("https://render.alipay.com/p/s/ulink/qd")
        val plain = UrlPicker.score("https://example.com/hello/world")
        assertTrue("签到链接应该排在前面", checkin > plain)
    }

    @Test
    fun `rank 把高分排前面`() {
        val ranked = UrlPicker.rank(
            listOf(
                "https://example.com/whatever/page",
                "https://render.alipay.com/p/s/ulink/qd",
            )
        )
        assertEquals("https://render.alipay.com/p/s/ulink/qd", ranked.first())
    }

    @Test
    fun `短字符串不算链接`() {
        assertFalse(UrlPicker.looksLikeUrl("abc"))
        assertFalse(UrlPicker.looksLikeUrl(""))
        assertTrue(UrlPicker.looksLikeUrl("https://a.com/xxxxxxxx"))
    }

    @Test
    fun `从转义过的源码里也能挖出链接`() {
        // 标签里常见 \u002f 这种转义
        val src = """var url = "https:\u002f\u002frender.alipay.com\u002fp\u002fs\u002fulink\u002fqd";"""
        val found = UrlPicker.extract(src)
        assertTrue("应该能挖出至少一条", found.isNotEmpty())
        assertTrue(found.any { it.contains("render.alipay.com") })
    }

    @Test
    fun `去重`() {
        val out = UrlPicker.rank(
            listOf(
                "https://render.alipay.com/p/s/ulink/qd",
                "https://render.alipay.com/p/s/ulink/qd",
            )
        )
        assertEquals(1, out.size)
    }
}
