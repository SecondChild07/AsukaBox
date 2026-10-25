package com.AusKa.ToolBox.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 高德「输入提示」接口（Web 服务）：把用户输入的关键词变成具体地点 + 坐标。
 *
 * 需要用户自己在设置里填一个高德 Key（开放平台免费申请，服务平台选「Web服务」）。
 * 为什么不做成内置：Key 是绑定到应用/域名的配额凭证，写死在开源仓库里等于把额度送给所有人，
 * 高德也会因为超限把整个 Key 封掉。让用自己的人填自己的，额度独立、随时可换。
 *
 * 返回的 location 是 GCJ-02（和地图瓦片同一套），所以拿到就能直接 setView，不用转换。
 */
internal object AmapSearch {

    private const val ENDPOINT = "https://restapi.amap.com/v3/assistant/inputtips"

    data class Tip(val name: String, val district: String, val lat: Double, val lng: Double)

    /** 返回可直接拼进 JS 的 JSON 数组字符串；出错或没 key 时返回 "[]" */
    fun searchJson(key: String, keyword: String, limit: Int = 8): String {
        val tips = runCatching { inputTips(key, keyword) }.getOrDefault(emptyList())
        val sb = StringBuilder("[")
        tips.take(limit).forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append("{\"n\":\"").append(esc(t.name))
                .append("\",\"d\":\"").append(esc(t.district))
                .append("\",\"lat\":").append(t.lat)
                .append(",\"lng\":").append(t.lng).append('}')
        }
        return sb.append(']').toString()
    }

    fun inputTips(key: String, keyword: String): List<Tip> {
        if (key.isBlank() || keyword.isBlank()) return emptyList()
        val q = URLEncoder.encode(keyword, "UTF-8")
        val k = URLEncoder.encode(key, "UTF-8")
        val url = URL("$ENDPOINT?key=$k&keywords=$q&datatype=all&output=json")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AsukaBox/1.0 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        val body = try {
            if (conn.responseCode !in 200..299) return emptyList()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }

        return parseTips(body)
    }

    /**
     * 解析高德 inputtips 的响应体。抽成纯函数是为了能单测 —— 没有真 key 也能验证字段名和坐标顺序。
     *
     * 返回体的关键形状（`location` 是 "经度,纬度"，GCJ-02）：
     * ```
     * {"status":"1","tips":[
     *   {"name":"天安门","district":"北京市东城区","location":"116.403873,39.915085"},
     *   {"name":"","district":"","location":""}      // 区县级的空提示，要跳过
     * ]}
     * ```
     * `status != "1"` 一般是 key 无效或超额，直接当没结果。
     */
    internal fun parseTips(body: String): List<Tip> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (root.optString("status") != "1") return emptyList()
        val tips = root.optJSONArray("tips") ?: return emptyList()

        val out = ArrayList<Tip>(tips.length())
        for (i in 0 until tips.length()) {
            val o = tips.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val loc = o.optString("location").trim()
            if (name.isEmpty() || loc.isEmpty()) continue
            val parts = loc.split(',')
            if (parts.size != 2) continue
            val lng = parts[0].toDoubleOrNull() ?: continue
            val lat = parts[1].toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
            out.add(Tip(name, o.optString("district").trim(), lat, lng))
        }
        return out
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")
}
