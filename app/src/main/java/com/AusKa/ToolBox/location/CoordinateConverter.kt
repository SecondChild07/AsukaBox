package com.AusKa.ToolBox.location

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84（GPS 原始坐标）与 GCJ-02（高德/腾讯地图用的火星坐标）互转。
 *
 * 为什么需要：高德瓦片上点出来的位置是 GCJ-02，而系统 GPS provider 给 App 的是 WGS-84。
 * 两者在国境内相差 100～700 米，直接用会让模拟位置偏出好几条街。
 */
internal object CoordinateConverter {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    private fun outOfChina(lat: Double, lon: Double): Boolean {
        // 大矩形之外：直接不偏移
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return true
        // 港澳台落在大矩形里，但 GCJ-02 只适用于大陆。
        // 不排除的话香港/澳门/台北会被加几百米偏移，明明选的是尖沙咀却落到海里。
        if (lon in 113.7..114.6 && lat in 22.0..22.7) return true   // 香港
        if (lon in 113.4..113.7 && lat in 21.9..22.3) return true   // 澳门
        if (lon in 119.9..122.1 && lat in 21.8..25.4) return true   // 台湾
        return false
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    /** WGS-84 → GCJ-02 */
    fun wgs84ToGcj02(lat: Double, lon: Double): DoubleArray {
        if (outOfChina(lat, lon)) return doubleArrayOf(lat, lon)
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return doubleArrayOf(lat + dLat, lon + dLon)
    }

    /** GCJ-02 → WGS-84（迭代逼近，两次就够到 1 米以内） */
    fun gcj02ToWgs84(lat: Double, lon: Double): DoubleArray {
        if (outOfChina(lat, lon)) return doubleArrayOf(lat, lon)
        var wLat = lat
        var wLon = lon
        repeat(2) {
            val g = wgs84ToGcj02(wLat, wLon)
            wLat -= g[0] - lat
            wLon -= g[1] - lon
        }
        return doubleArrayOf(wLat, wLon)
    }
}
