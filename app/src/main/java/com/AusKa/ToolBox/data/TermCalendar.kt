package com.AusKa.ToolBox.data

import java.util.Calendar
import java.util.Locale

/**
 * 学期日历：把「第 N 周」和真实日期对上。
 *
 * 正方只给周次（`zcd`），不给开学日期，也不透露当前是第几周，
 * 所以这里做两件事：
 *  1. 导入时按学期估一个起始日（秋季按 9 月、春季按 2 月底）；
 *  2. 课表页允许用户校准一次（「今天这周是第 5 周」比让他回忆开学日期容易）。
 */
object TermCalendar {

    /** 估算第 1 周周一。term：3 = 秋季，12 = 春季，16 = 短学期 */
    fun estimateStart(year: Int, term: Int): Calendar {
        val c = Calendar.getInstance()
        when (term) {
            12 -> c.set(year + 1, Calendar.FEBRUARY, 24)
            16 -> c.set(year + 1, Calendar.JULY, 1)
            else -> c.set(year, Calendar.SEPTEMBER, 1)
        }
        return mondayOf(c)
    }

    /** 把任意日期归到那一周的周一（周一为一周之首，和课表对得上） */
    fun mondayOf(day: Calendar): Calendar {
        val c = day.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val dow = c.get(Calendar.DAY_OF_WEEK) // Sunday = 1
        val delta = if (dow == Calendar.SUNDAY) -6 else Calendar.MONDAY - dow
        c.add(Calendar.DAY_OF_MONTH, delta)
        return c
    }

    fun parse(date: String): Calendar? {
        if (date.length < 10) return null
        val parts = date.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        return Calendar.getInstance().apply {
            set(y, m - 1, d, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    fun format(date: Calendar): String = String.format(
        Locale.US, "%04d-%02d-%02d",
        date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH),
    )

    /** 第 week 周的周一 */
    fun weekStart(startDate: String, week: Int, estimate: () -> Calendar): Calendar {
        val start = parse(startDate) ?: estimate()
        val c = start.clone() as Calendar
        c.add(Calendar.DAY_OF_MONTH, (week - 1) * 7)
        return c
    }

    /** 今天落在第几周；学期还没开始就返回 1，超出总周数就贴到末尾 */
    fun currentWeek(startDate: String, maxWeek: Int, estimate: () -> Calendar): Int {
        val start = parse(startDate) ?: return 1
        val startMonday = mondayOf(start)
        val today = mondayOf(Calendar.getInstance())
        val days = ((today.timeInMillis - startMonday.timeInMillis) / 86_400_000L).toInt()
        val week = days / 7 + 1
        return week.coerceIn(1, maxWeek)
    }

    /** 由「今天这周是第 n 周」反推第 1 周周一 */
    fun startFromCurrentWeek(currentWeek: Int): String {
        val monday = mondayOf(Calendar.getInstance())
        monday.add(Calendar.DAY_OF_MONTH, -(currentWeek - 1) * 7)
        return format(monday)
    }

    /** 月/日，给表头用 */
    fun monthDay(date: Calendar): String =
        "${date.get(Calendar.MONTH) + 1}/${date.get(Calendar.DAY_OF_MONTH)}"

    /** 今天是周几（1 = 周一 … 7 = 周日） */
    fun dayOfWeekIndex(date: Calendar = Calendar.getInstance()): Int {
        val dow = date.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }
}
