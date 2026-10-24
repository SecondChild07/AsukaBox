package com.AusKa.ToolBox

import com.AusKa.ToolBox.location.CoordinateConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * GCJ-02 与 WGS-84 的互转。
 *
 * 高德瓦片是 GCJ-02，系统 GPS provider 收的是 WGS-84 —— 直接把地图上点到的坐标写进去，
 * 在北京要偏 600 米左右。反解没有闭式解，用的是两次迭代逼近。
 */
class CoordinateTest {

    private fun near(a: Double, b: Double, eps: Double = 2e-5) = abs(a - b) < eps

    @Test
    fun `正向转换会把北京的点挪几百米`() {
        val (lat, lon) = CoordinateConverter.wgs84ToGcj02(39.908823, 116.397470).let { it[0] to it[1] }
        assertTrue("纬度应该往北挪", lat > 39.908823)
        assertTrue("经度应该往东挪", lon > 116.397470)
        // 粗略量级检查：偏移在 0.0005 ~ 0.01 度之间
        assertTrue(abs(lat - 39.908823) in 0.0005..0.01)
        assertTrue(abs(lon - 116.397470) in 0.0005..0.01)
    }

    @Test
    fun `往返转换能回到原点`() {
        val originLat = 39.908823
        val originLon = 116.397470
        val gcj = CoordinateConverter.wgs84ToGcj02(originLat, originLon)
        val back = CoordinateConverter.gcj02ToWgs84(gcj[0], gcj[1])
        assertTrue("往返误差应在 1 米内（纬度）", near(back[0], originLat))
        assertTrue("往返误差应在 1 米内（经度）", near(back[1], originLon))
    }

    @Test
    fun `境外坐标原样返回`() {
        // 东京不在 GCJ-02 的偏移范围里
        val out = CoordinateConverter.wgs84ToGcj02(35.6895, 139.6917)
        assertEquals(35.6895, out[0], 0.0)
        assertEquals(139.6917, out[1], 0.0)
    }
}
