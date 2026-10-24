package com.AusKa.ToolBox.data

import org.json.JSONObject

/** 课程配色：课表格子和桌面小组件共用，同一门课到哪都一个颜色 */
private val COURSE_COLORS = intArrayOf(
    0xFF4F6BFF.toInt(), 0xFF00A6A6.toInt(), 0xFFFF8A3D.toInt(), 0xFF8B5CF6.toInt(),
    0xFF3DBE6B.toInt(), 0xFFE05A7A.toInt(), 0xFF2E9BD6.toInt(), 0xFFB07A2A.toInt(),
)

fun courseColor(name: String): Int {
    if (name.isEmpty()) return COURSE_COLORS[0]
    val i = ((name.hashCode() % COURSE_COLORS.size) + COURSE_COLORS.size) % COURSE_COLORS.size
    return COURSE_COLORS[i]
}

/**
 * 一节课（同一天同一时段的一门课算一条）。
 *
 * 正方给的原始字段是「星期几 + 第几节到第几节 + 周次字符串」，这里已经拆成可用的结构，
 * UI 只需要按 [day] 和 [startSection] 定位。
 */
data class Course(
    val name: String,
    val teacher: String,
    val room: String,
    val day: Int,          // 1 = 周一 … 7 = 周日
    val startSection: Int, // 1 起
    val endSection: Int,
    val weeks: Set<Int>,
    val campus: String = "",
    /** 正方原始周次串，例如 `1-3周,6-8周(双),9-18周`；存下来是为了以后改解析规则能重算 */
    val zcd: String = "",
) {
    val sectionSpan: Int get() = endSection - startSection + 1

    /**
     * 空集当作「每周都有」。
     *
     * 正方某些部署的 `zcd` 是空的或者格式认不出来，[parseWeeks] 会返回空集。
     * 原来直接 `weeks.contains(week)` 会让这门课**所有周都不显示、且没有任何提示** ——
     * 用户只会觉得「课表少了几门」。
     */
    fun inWeek(week: Int): Boolean = weeks.isEmpty() || weeks.contains(week)

    companion object {

        /**
         * 解析正方 `zcd` 字段，形如：
         *  - `1-16周`
         *  - `1-8,10-16周`
         *  - `3周`
         *  - `1-15周(单)` / `2-16周(双)`
         *  - `1-16周(1-2节)`
         */
        fun parseWeeks(raw: String): Set<Int> {
            if (raw.isBlank()) return emptySet()

            val result = sortedSetOf<Int>()
            raw.replace('（', '(').replace('）', ')')
                .replace('，', ',').replace('；', ',').replace(';', ',')
                .split(',')
                .forEach { seg ->
                    val piece = seg.trim()
                    if (piece.isEmpty()) return@forEach

                    // 单双周是**段级**的：`1-3周,6-8周(双),9-18周` 里只有 6-8 段是双周，
                    // 不能拿整串去筛奇偶（早期版本就是这么错的，导致 9-18 整段被当成双周丢掉）
                    val odd = piece.contains("单")
                    val even = piece.contains("双")

                    // 括号里一般是节次说明（1-2节），先切掉
                    val core = piece.substringBefore('(')
                    val nums = Regex("""\d+""").findAll(core)
                        .mapNotNull { it.value.toIntOrNull() }
                        .toList()
                    if (nums.isEmpty()) return@forEach

                    val a = nums.first()
                    val b = if (nums.size >= 2) nums[1] else a
                    for (w in a..maxOf(a, b)) {
                        if (odd && w % 2 == 0) continue
                        if (even && w % 2 == 1) continue
                        result.add(w)
                    }
                }

            return result
        }

        /** 解析 `jcs` 字段：`1-2` / `5` / `3-4节` / 四位数 `0506` → Pair(起始, 结束) */
        fun parseSections(raw: String): Pair<Int, Int> {
            val cleaned = raw.replace("节", "").trim()
            // 部分部署用「两位起 + 两位止」的紧凑写法：0506 表示第 5-6 节。
            // 不做这个判断会当成 506 节，格子直接画到天外
            Regex("""^(\d{2})(\d{2})$""").find(cleaned)?.let { m ->
                val a = m.groupValues[1].toIntOrNull() ?: 0
                val b = m.groupValues[2].toIntOrNull() ?: 0
                if (a in 1..20 && b in 1..20) return a to maxOf(a, b)
            }
            if (cleaned.contains('-')) {
                val parts = cleaned.split('-')
                val a = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
                val b = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: a
                return a to maxOf(a, b)
            }
            val single = cleaned.toIntOrNull() ?: 1
            return single to single
        }

        /** 从正方 `kbList` 的一条记录里建课程；字段缺失就跳过 */
        fun fromJson(o: JSONObject): Course? {
            val name = o.optString("kcmc").ifBlank { o.optString("kcbm") }
            if (name.isBlank()) return null

            val day = o.optString("xqj").toIntOrNull() ?: return null
            val (start, end) = parseSections(o.optString("jcs"))

            val zcd = o.optString("zcd")
            return Course(
                name = name,
                teacher = o.optString("xm"),
                room = o.optString("cdmc"),
                day = day.coerceIn(1, 7),
                startSection = start.coerceAtLeast(1),
                endSection = end.coerceAtLeast(start),
                weeks = parseWeeks(zcd),
                campus = o.optString("xqmc"),
                zcd = zcd,
            )
        }
    }
}

/** 一整个学期的课表 */
data class Timetable(
    val courses: List<Course>,
    val year: Int,
    val term: Int,
    val studentName: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    /** 第 1 周周一的日期，`yyyy-MM-dd`。正方不给开学日期，所以由 App 估算 + 用户校准 */
    val startDate: String = "",
    /** 这学期按多少周算（下限）。原来写死 16，18 周的学期第 17/18 周翻不过去 */
    val weeksInTerm: Int = 20,
) {
    /** 这学期一共有多少周：课程里出现过的最大周次，但不低于 [weeksInTerm] */
    val maxWeek: Int
        get() = maxOf(weeksInTerm, courses.flatMap { it.weeks }.maxOrNull() ?: 0)

    fun ofWeek(week: Int): List<Course> = courses.filter { it.inWeek(week) }

    /** 按 天 + 起始节 排好，方便 UI 直接铺 */
    fun grid(week: Int): List<Course> =
        ofWeek(week).sortedWith(compareBy({ it.day }, { it.startSection }))
}
