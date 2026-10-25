package com.AusKa.ToolBox

import com.AusKa.ToolBox.net.AmapSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高德 inputtips 响应解析。
 *
 * 没有真 key 的情况下，这是唯一能离线验证「字段名 / 坐标顺序 / 空条目过滤」的地方 ——
 * 字段写错的话线上就是「搜索永远没结果」，而且很难从 UI 上看出来是接口挂了还是解析挂了。
 */
class AmapSearchTest {

    @Test
    fun `正常响应能解析出名称和坐标`() {
        val body = """
            {"status":"1","info":"OK","count":"2","tips":[
              {"name":"天安门","district":"北京市东城区","location":"116.403873,39.915085"},
              {"name":"天安门广场","district":"北京市东城区","location":"116.397499,39.900902"}
            ]}
        """.trimIndent()
        val tips = AmapSearch.parseTips(body)
        assertEquals(2, tips.size)
        assertEquals("天安门", tips[0].name)
        assertEquals("北京市东城区", tips[0].district)
        // location 是「经度,纬度」，别把顺序弄反 —— 反了就会定位到南极洲附近
        assertEquals(116.403873, tips[0].lng, 1e-6)
        assertEquals(39.915085, tips[0].lat, 1e-6)
    }

    @Test
    fun `空 location 的区县提示要跳过`() {
        val body = """
            {"status":"1","tips":[
              {"name":"","district":"","location":""},
              {"name":"西湖","district":"浙江省杭州市西湖区","location":"120.149,30.242"},
              {"name":"","location":""}
            ]}
        """.trimIndent()
        val tips = AmapSearch.parseTips(body)
        assertEquals(1, tips.size)
        assertEquals("西湖", tips[0].name)
    }

    @Test
    fun `status 不是 1 时返回空`() {
        val body = """{"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}"""
        assertTrue(AmapSearch.parseTips(body).isEmpty())
    }

    @Test
    fun `坏 JSON 不抛异常`() {
        assertTrue(AmapSearch.parseTips("<html>captcha</html>").isEmpty())
        assertTrue(AmapSearch.parseTips("").isEmpty())
    }

    @Test
    fun `坐标超界的条目丢掉`() {
        val body = """
            {"status":"1","tips":[
              {"name":"坏点","district":"","location":"200.0,95.0"},
              {"name":"好点","district":"","location":"116.4,39.9"}
            ]}
        """.trimIndent()
        val tips = AmapSearch.parseTips(body)
        assertEquals(1, tips.size)
        assertEquals("好点", tips[0].name)
    }
}
