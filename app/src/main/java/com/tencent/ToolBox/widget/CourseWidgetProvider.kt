package com.tencent.ToolBox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.tencent.ToolBox.MainActivity
import com.tencent.ToolBox.R
import com.tencent.ToolBox.data.Course
import com.tencent.ToolBox.data.SectionTimes
import com.tencent.ToolBox.data.TermCalendar
import com.tencent.ToolBox.data.Timetable
import com.tencent.ToolBox.data.TimetableStore
import com.tencent.ToolBox.data.courseColor
import java.util.Calendar

/**
 * 桌面小组件：今天上什么课。
 *
 * 数据直接从 [TimetableStore] 读（那边已经是解密后的内存副本），不需要 Service、
 * 不需要 RemoteViewsService —— 行数最多也就十来条，用 [RemoteViews.addView] 现场拼就行。
 *
 * 刷新时机：系统按 `updatePeriodMillis`（30 分钟）、App 启动 / 同步完 / 导入完主动调 [refreshAll]。
 */
class CourseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> runCatching { render(context, appWidgetManager, id) } }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAll(context)
    }

    companion object {

        const val ACTION_REFRESH = "com.tencent.ToolBox.action.WIDGET_REFRESH"

        /** 卡片里除了课程行之外的固定开销（内边距 + 头部 + 标题 + 脚注，单位 dp） */
        private const val FIXED_HEIGHT_DP = 84
        /** 一行课占的高度（dp），和 widget_course_row.xml 对齐 */
        private const val ROW_HEIGHT_DP = 42
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

            // 按 launcher 实际给的格子高度决定放几行，用户把卡片拉大就能多看几门
            val maxHeightDp = manager.getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 160)
            val maxRows = ((maxHeightDp - FIXED_HEIGHT_DP) / ROW_HEIGHT_DP).coerceIn(1, 6)

            views.setTextViewText(
                R.id.widget_header,
                "第 $week 周 · ${TermCalendar.monthDay(today)} 周${DAY_NAMES[dow - 1]}",
            )
            views.removeAllViews(R.id.widget_content)

            if (list.isEmpty()) {
                views.setViewVisibility(R.id.widget_title, View.GONE)
                views.setViewVisibility(R.id.widget_footer, View.GONE)
                views.addView(
                    R.id.widget_content,
                    hintText(context, "今日无课", "第 $week 周 · 周${DAY_NAMES[dow - 1]}"),
                )
            } else {
                views.setViewVisibility(R.id.widget_title, View.VISIBLE)
                views.setTextViewText(R.id.widget_title, "今天 ${list.size} 门")
                list.take(maxRows).forEach { course ->
                    views.addView(R.id.widget_content, buildRow(context, course))
                }
                val rest = list.size - maxRows
                if (rest > 0) {
                    views.setTextViewText(R.id.widget_footer, "还有 $rest 门 · 点开看整周")
                    views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.widget_footer, View.GONE)
                }
            }

            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            manager.updateAppWidget(id, views)
        }

        private fun buildRow(context: Context, course: Course): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_course_row)
            row.setTextViewText(R.id.row_section, if (course.sectionSpan > 1) "${course.startSection}-${course.endSection}" else "${course.startSection}")
            row.setTextViewText(R.id.row_name, course.name)
            row.setTextViewText(R.id.row_room, course.room.ifBlank { "地点未排" })
            row.setInt(R.id.row_bar, "setColorFilter", courseColor(course.name))
            return row
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
