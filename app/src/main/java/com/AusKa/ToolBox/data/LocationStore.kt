package com.AusKa.ToolBox.data

import android.content.Context

/**
 * 虚拟定位用到的本地数据。
 *
 * 坐标和名字都不敏感（顶多是"我常去学校"这种），所以这里用普通 SharedPreferences，
 * 没走 SecretBox —— 加密那些是给账号密码和课表用的。
 */
class LocationStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("mock_location", Context.MODE_PRIVATE)

    /** 上一次用的坐标，界面打开时回填 */
    var lastLat: Double
        get() = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: DEFAULT_LAT
        set(value) = prefs.edit().putString(KEY_LAT, value.toString()).apply()

    var lastLon: Double
        get() = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: DEFAULT_LON
        set(value) = prefs.edit().putString(KEY_LON, value.toString()).apply()

    var lastName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    private companion object {
        const val KEY_LAT = "last_lat"
        const val KEY_LON = "last_lon"
        const val KEY_NAME = "last_name"

        // 默认值给天安门，第一次打开也有个能点的东西
        const val DEFAULT_LAT = 39.908823
        const val DEFAULT_LON = 116.397470
    }
}
