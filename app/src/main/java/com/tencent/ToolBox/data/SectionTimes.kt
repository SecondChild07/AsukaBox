package com.tencent.ToolBox.data

/**
 * 上课时间表。
 *
 * 正方教务只给「第几节」，不给几点几分（`sjkList` 里也只有服务器当天日期），
 * 所以作息时间只能由客户端定一份。这里用的是国内高校最常见的一版：
 * 一节 45 分钟，上午 4 节、下午 4 节、晚上 4 节。
 *
 * 如果你们学校的作息不一样，改下面两个数组就行 —— 索引 0 对应第 1 节。
 */
object SectionTimes {

    private val STARTS = arrayOf(
        "08:00", "08:55", "10:00", "10:55",
        "14:00", "14:55", "16:00", "16:55",
        "19:00", "19:55", "20:50", "21:45",
    )

    private val ENDS = arrayOf(
        "08:45", "09:40", "10:45", "11:40",
        "14:45", "15:40", "16:45", "17:40",
        "19:45", "20:40", "21:35", "22:30",
    )

    /** 第 [section] 节的上课时间，越界返回空串 */
    fun start(section: Int): String = STARTS.getOrElse(section - 1) { "" }

    /** 第 [section] 节的下课时间，越界返回空串 */
    fun end(section: Int): String = ENDS.getOrElse(section - 1) { "" }

    /** `08:00-09:40` 这种一行式写法，用于详情弹窗 */
    fun range(from: Int, to: Int): String {
        val s = start(from)
        val e = end(to)
        return when {
            s.isEmpty() && e.isEmpty() -> ""
            s.isEmpty() -> e
            e.isEmpty() -> s
            else -> "$s-$e"
        }
    }

    /** 小组件里竖排的 `08:00\n09:40` */
    fun span(from: Int, to: Int): String {
        val s = start(from)
        val e = end(to)
        return when {
            s.isEmpty() -> e
            e.isEmpty() -> s
            else -> "$s\n$e"
        }
    }
}
