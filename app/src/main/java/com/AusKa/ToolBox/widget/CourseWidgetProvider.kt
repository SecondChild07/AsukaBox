package com.AusKa.ToolBox.widget

import android.os.Bundle
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.AusKa.ToolBox.MainActivity
import com.AusKa.ToolBox.R
import com.AusKa.ToolBox.data.Course
import com.AusKa.ToolBox.data.TermCalendar
import com.AusKa.ToolBox.data.TimetableStore
import com.AusKa.ToolBox.data.courseColor
import java.util.Calendar

/** 卡片上下内边距之和（widget_course.xml paddingVertical 10dp × 2） */
private const val PADDING_DP = 20

/** 顶部那行日期（10sp，视觉高度约 15dp） */
private const val HEADER_DP = 15

/** 标题「今天 N 门」（15sp bold，含 2dp margin） */
private const val TITLE_DP = 22

/** widget_content 的 marginTop */
private const val CONTENT_MARGIN_DP = 5

/** 两行式课程行的实高（widget_course_row.xml：色条 24 + 上下 padding 12 + marginBottom 6 ≈ 47dp） */
private const val ROW_TWO_LINE_DP = 47

/** 单行式课程行的实高（widget_course_row_compact.xml，约 24dp） */
private const val ROW_ONE_LINE_DP = 24

/** 脚注「还有 N 门」那行 */
private const val FOOTER_DP = 20

/** launcher 还没给 OPTION_APPWIDGET_MAX_HEIGHT 时的兜底高度，按 3×2 估 */
private const val FALLBACK_HEIGHT_DP = 150

/** 课程行有两种排法：带地点的两行版，和挤成一条的单行版 */
internal enum class RowStyle { TwoLine, OneLine }

/** 这次重排的结果：用哪种行、几行、要不要头部/标题 */
internal data class WidgetPlan(
    val style: RowStyle,
    val rows: Int,
    val showHeader: Boolean,
    val showTitle: Boolean,
)

/** 一行课在当前字体缩放下实际占多高 */
internal fun rowHeightDp(style: RowStyle, fontScale: Float = 1f): Int {
    val scale = fontScale.coerceIn(0.85f, 1.4f)
    return when (style) {
        RowStyle.TwoLine -> (ROW_TWO_LINE_DP * scale).toInt()
        RowStyle.OneLine -> (ROW_ONE_LINE_DP * scale).toInt()
    }
}

/**
 * 按卡片实际高度决定怎么塞下今天的课。
 *
 * 思路是从「好看」往「能装」逐级降级，第一档装得下就停：
 *  ① 两行版 + 日期 + 标题
 *  ② 两行版 + 日期（让掉标题）
 *  ③ 单行版 + 日期 + 标题
 *  ④ 单行版 + 日期
 *  ⑤ 单行版（日期也让掉）
 * 全都不够就截断，并优先保住日期行 —— 日期每天都要看，多一门少一门可以通过脚注提示拉高卡片。
 */
internal fun widgetPlan(maxHeightDp: Int, count: Int, fontScale: Float = 1f): WidgetPlan {
    if (count <= 0) return WidgetPlan(RowStyle.TwoLine, 0, true, true)

    val scale = fontScale.coerceIn(0.85f, 1.4f)
    val header = (HEADER_DP * scale).toInt()
    val title = (TITLE_DP * scale).toInt()
    val footer = (FOOTER_DP * scale).toInt()
    val twoLine = rowHeightDp(RowStyle.TwoLine, scale)
    val oneLine = rowHeightDp(RowStyle.OneLine, scale)

    fun budget(withHeader: Boolean, withTitle: Boolean, withFooter: Boolean): Int {
        var used = PADDING_DP + CONTENT_MARGIN_DP
        if (withHeader) used += header
        if (withTitle) used += title
        if (withFooter) used += footer
        return maxHeightDp - used
    }

    val ladder = listOf(
        WidgetPlan(RowStyle.TwoLine, count, showHeader = true, showTitle = true),
        WidgetPlan(RowStyle.TwoLine, count, showHeader = true, showTitle = false),
        WidgetPlan(RowStyle.OneLine, count, showHeader = true, showTitle = true),
        WidgetPlan(RowStyle.OneLine, count, showHeader = true, showTitle = false),
        WidgetPlan(RowStyle.OneLine, count, showHeader = false, showTitle = false),
    )
    ladder.forEach { plan ->
        val row = rowHeightDp(plan.style, scale)
        if (count * row <= budget(plan.showHeader, plan.showTitle, withFooter = false)) {
            return plan
        }
    }

    // 装不下：留一行脚注，标题一定让掉，最后比较「留日期」和「多塞一门」哪个更划算
    val cap = (count - 1).coerceAtLeast(1)
    val withHeaderRows = (budget(true, false, true) / oneLine).coerceIn(1, cap)
    val noHeaderRows = (budget(false, false, true) / oneLine).coerceIn(1, cap)
    return if (noHeaderRows > withHeaderRows) {
        WidgetPlan(RowStyle.OneLine, noHeaderRows, showHeader = false, showTitle = false)
    } else {
        WidgetPlan(RowStyle.OneLine, withHeaderRows, showHeader = true, showTitle = false)
    }
}

/**
 * 桌面小组件：今天上什么课。
 *
 * 数据直接从 [TimetableStore] 读（那边已经是解密后的内存副本），不需要 Service、
 * 不需要 RemoteViewsService —— 行数最多也就十来条，用 [RemoteViews.addView] 现场拼就行。
 *
 * 刷新时机：系统按 `updatePeriodMillis`（30 分钟）、App 启动 / 同步完 / 导入完主动调 [refreshAll]。
 */
class CourseWidgetProvider : AppWidgetProvider() {

    /** 用户拖动改变小组件尺寸时立刻重排：原来只有 30 分钟周期刷新才会变 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        runCatching { render(context, appWidgetManager, appWidgetId) }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> runCatching { render(context, appWidgetManager, id) } }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAll(context)
    }

    companion object {

        const val ACTION_REFRESH = "com.AusKa.ToolBox.action.WIDGET_REFRESH"

        /** 星期几的中文简称，下标 0 = 周一 */
        private val DAY_NAMES = arrayOf("一", "二", "三", "四", "五", "六", "日")

        /** 把所有已放上桌面的实例重画一遍 */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context.applicationContext) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context.applicationContext, CourseWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            ids.forEach { id -> runCatching { render(context.applicationContext, manager, id) } }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_course)
            val store = TimetableStore.get(context)
            val timetable = store.load()

            if (timetable == null) {
                views.setTextViewText(R.id.widget_header, "明日箱")
                views.setViewVisibility(R.id.widget_title, View.GONE)
                views.setViewVisibility(R.id.widget_footer, View.GONE)
                views.removeAllViews(R.id.widget_content)
                views.addView(
                    R.id.widget_content,
                    hintText(context, "还没导入课表", "点一下打开 App，从教务系统抓一次"),
                )
                views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
                manager.updateAppWidget(id, views)
                return
            }

            val today = Calendar.getInstance()
            val week = TermCalendar.currentWeek(timetable.startDate, timetable.maxWeek) {
                TermCalendar.estimateStart(timetable.year, timetable.term)
            }
            val dow = TermCalendar.dayOfWeekIndex(today)
            val list = timetable.ofWeek(week)
                .filter { it.day == dow }
                .sortedBy { it.startSection }

            // 按 launcher 实际给出的格子高度决定用哪种行、放几门
            val maxHeightDp = manager.getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                .takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP
            val fontScale = context.resources.configuration.fontScale
            val plan = widgetPlan(maxHeightDp, list.size, fontScale)

            views.setTextViewText(
                R.id.widget_header,
                "第 $week 周 · ${TermCalendar.monthDay(today)} 周${DAY_NAMES[dow - 1]}",
            )
            views.setViewVisibility(
                R.id.widget_header,
                if (plan.showHeader) View.VISIBLE else View.GONE,
            )
            views.removeAllViews(R.id.widget_content)

            if (list.isEmpty()) {
                views.setViewVisibility(R.id.widget_title, View.GONE)
                views.setViewVisibility(R.id.widget_footer, View.GONE)
                views.addView(
                    R.id.widget_content,
                    hintText(context, "今日无课", "第 $week 周 · 周${DAY_NAMES[dow - 1]}"),
                )
                views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
                manager.updateAppWidget(id, views)
                return
            }

            views.setViewVisibility(
                R.id.widget_title,
                if (plan.showTitle) View.VISIBLE else View.GONE,
            )
            if (plan.showTitle) views.setTextViewText(R.id.widget_title, "今天 ${list.size} 门")

            list.take(plan.rows).forEach { course ->
                views.addView(R.id.widget_content, buildRow(context, course, plan.style))
            }

            val rest = list.size - plan.rows
            when {
                rest > 0 -> {
                    views.setTextViewText(R.id.widget_footer, "还有 $rest 门 · 把卡片拉高")
                    views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
                }
                !plan.showTitle -> {
                    // 标题被让掉了，用脚注补一句总数，免得看着像漏课
                    views.setTextViewText(
                        R.id.widget_footer,
                        if (plan.showHeader) {
                            "今天 ${list.size} 门 · 全在这"
                        } else {
                            "第 $week 周 · ${list.size} 门全在这"
                        },
                    )
                    views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
                }
                else -> views.setViewVisibility(R.id.widget_footer, View.GONE)
            }

            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            manager.updateAppWidget(id, views)
        }

        private fun buildRow(context: Context, course: Course, style: RowStyle): RemoteViews {
            val layout = if (style == RowStyle.TwoLine) {
                R.layout.widget_course_row
            } else {
                R.layout.widget_course_row_compact
            }
            val row = RemoteViews(context.packageName, layout)
            row.setTextViewText(R.id.row_section, sectionLabel(course))
            row.setTextViewText(R.id.row_name, course.name)
            if (style == RowStyle.TwoLine) {
                row.setTextViewText(R.id.row_room, course.room.ifBlank { "地点未排" })
            } else if (course.room.isBlank()) {
                row.setViewVisibility(R.id.row_room, View.GONE)
            } else {
                row.setTextViewText(R.id.row_room, course.room)
            }
            row.setInt(R.id.row_bar, "setColorFilter", courseColor(course.name))
            return row
        }

        /** 连堂课显示「3-4」，单节只显示起始节 */
        private fun sectionLabel(course: Course): String =
            if (course.sectionSpan > 1) {
                "${course.startSection}-${course.endSection}"
            } else {
                "${course.startSection}"
            }

        /** 空态用的两行字：一句小标题 + 一句正文，居中放在卡片中间那块 */
        private fun hintText(context: Context, label: String, detail: String): RemoteViews {
            val box = RemoteViews(context.packageName, R.layout.widget_hint)
            box.setTextViewText(R.id.hint_label, label)
            if (detail.isBlank()) {
                box.setViewVisibility(R.id.hint_detail, View.GONE)
            } else {
                box.setTextViewText(R.id.hint_detail, detail)
                box.setViewVisibility(R.id.hint_detail, View.VISIBLE)
            }
            return box
        }

        private fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
