package com.AusKa.ToolBox.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.AusKa.ToolBox.security.Crypt

/**
 * 每节课的上下课时间。教务只给「第几节」，作息得自己定。
 *
 * 默认值是国内高校最常见的一版（上午 4 节、下午 4 节、晚上 4 节，每节 45 分钟），
 * 用户可以在「设置 → 上课时间」里逐个改，改完立刻写到本机、课表页马上跟着变。
 */
internal object SectionTimesStore {

    /** 一节课 45 分钟，默认 12 节；13、14 节这种由用户自己填 */
    const val SECTIONS = 12

    /** 改过之后 +1，课表页读一下就会重组 */
    var revision by mutableIntStateOf(0)
        private set

    val defaultStarts: List<String> = listOf(
        "08:00", "08:55", "10:00", "10:55",
        "14:00", "14:55", "16:00", "16:55",
        "19:00", "19:55", "20:50", "21:45",
    )

    val defaultEnds: List<String> = listOf(
        "08:45", "09:40", "10:45", "11:40",
        "14:45", "15:40", "16:45", "17:40",
        "19:45", "20:40", "21:35", "22:30",
    )

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(Crypt.decode(Crypt.PREFS) + "_times", Context.MODE_PRIVATE)

    /** 第 [section] 节的开始时间（1 起），没配就用默认值 */
    fun start(context: Context, section: Int): String {
        val custom = prefs(context).getString("start_$section", null)
        if (!custom.isNullOrBlank()) return custom
        return defaultStarts.getOrElse(section - 1) { "" }
    }

    /** 第 [section] 节的结束时间（1 起） */
    fun end(context: Context, section: Int): String {
        val custom = prefs(context).getString("end_$section", null)
        if (!custom.isNullOrBlank()) return custom
        return defaultEnds.getOrElse(section - 1) { "" }
    }

    /** `08:00-09:40`：跨节的课取第一节开始到最后一节结束 */
    /** 小组件里竖排的 `08:00\n09:40` */
    fun span(context: Context, from: Int, to: Int): String {
        val s = start(context, from)
        val e = end(context, to)
        return when {
            s.isEmpty() -> e
            e.isEmpty() -> s
            else -> s + "\n" + e
        }
    }

    fun range(context: Context, from: Int, to: Int): String {
        val s = start(context, from)
        val e = end(context, to)
        return when {
            s.isEmpty() && e.isEmpty() -> ""
            s.isEmpty() -> e
            e.isEmpty() -> s
            else -> "$s-$e"
        }
    }

    fun hasCustom(context: Context): Boolean =
        prefs(context).all.keys.any { it.startsWith("start_") || it.startsWith("end_") }

    fun save(context: Context, section: Int, start: String, end: String) {
        prefs(context).edit().apply {
            if (start.isBlank()) remove("start_$section") else putString("start_$section", start.trim())
            if (end.isBlank()) remove("end_$section") else putString("end_$section", end.trim())
        }.apply()
        revision++
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
        revision++
    }

    /** 只接受 `H:mm` / `HH:mm`，其他一律当没填 */
    fun parseTime(raw: String): String? {
        val m = Regex("^\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})\\s*$").find(raw) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val mi = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || mi !in 0..59) return null
        return "%02d:%02d".format(h, mi)
    }
}
