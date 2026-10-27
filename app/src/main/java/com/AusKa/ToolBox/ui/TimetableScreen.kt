package com.AusKa.ToolBox.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.AusKa.ToolBox.data.Course
import com.AusKa.ToolBox.data.SectionTimesStore
import com.AusKa.ToolBox.data.TermCalendar
import com.AusKa.ToolBox.data.Timetable
import com.AusKa.ToolBox.data.TimetableStore
import com.AusKa.ToolBox.data.courseColor
import com.AusKa.ToolBox.glass.GlassButton
import com.AusKa.ToolBox.glass.GlassButtonKind
import com.AusKa.ToolBox.glass.LiquidGlass
import com.AusKa.ToolBox.glass.LocalGlassPalette
import com.AusKa.ToolBox.net.JwglxtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

internal fun colorFor(name: String): Color = Color(courseColor(name))

/** 默认学年学期：8 月之后算秋季学期，否则算春季学期 */
internal fun defaultTerm(): Pair<Int, Int> {
    val cal = Calendar.getInstance()
    val month = cal.get(Calendar.MONTH) + 1
    val year = cal.get(Calendar.YEAR)
    return if (month >= 8) year to 3 else (year - 1) to 12
}

internal fun termLabel(year: Int, term: Int): String = when (term) {
    3 -> "$year-${year + 1} 学年 · 第一学期"
    12 -> "$year-${year + 1} 学年 · 第二学期"
    else -> "$year-${year + 1} 学年 · 短学期"
}

@Composable
fun TimetableScreen(
    store: TimetableStore,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    // 启动时的后台同步会更新 store 里的 flow，这里跟着变
    val timetable by store.timetableFlow.collectAsState()
    val syncOk by store.syncOkFlow.collectAsState()
    var week by remember { mutableStateOf(0) }
    var showImport by remember { mutableStateOf(false) }
    var showCalibrate by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Course?>(null) }

    // 数据一来（或换了一份）就落到当前周；之后用户手动切周就不再打断
    LaunchedEffect(timetable) {
        val t = timetable ?: return@LaunchedEffect
        if (week <= 0 || week > t.maxWeek) week = weekOf(t)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
    ) {
        val current = timetable
        if (current == null) {
            EmptyTimetable(onImport = { showImport = true })
        } else {
            val t = current
            WeekHeader(
                timetable = t,
                week = week,
                syncOk = syncOk,
                lastSyncAt = store.lastSyncAt,
                onWeekChange = { week = it },
                onReimport = { showImport = true },
                onCalibrate = { showCalibrate = true },
            )
            Spacer(Modifier.height(10.dp))
            TimetableGrid(
                timetable = t,
                week = week,
                onCourseClick = { detail = it },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showImport) {
        ImportDialog(
            store = store,
            onDismiss = { showImport = false },
            onImported = { tt ->
                // 落盘只在这一个地方。开学日期同一学期才沿用 —— 服务端 startDate 永远为空，
                // 无条件重估会冲掉用户校准过的第 N 周；而换学期时照抄旧日期，当前周会直接顶到末尾
                val fixed = tt.copy(startDate = TermCalendar.startDateFor(tt, store.load()))
                if (store.save(fixed)) store.markSync(true)
                week = weekOf(fixed)
                showImport = false
            },
        )
    }

    val calibrating = if (showCalibrate) timetable else null
    if (calibrating != null) {
        val t = calibrating
        WeekCalibrateDialog(
            currentWeek = week,
            maxWeek = t.maxWeek,
            onDismiss = { showCalibrate = false },
            onPick = { picked ->
                val updated = t.copy(startDate = TermCalendar.startFromCurrentWeek(picked))
                store.save(updated)
                week = picked
                showCalibrate = false
            },
        )
    }

    detail?.let { course ->
        CourseDetailDialog(course = course, week = week, onDismiss = { detail = null })
    }
}

/** 打开课表时落在哪一周：按今天的日期算，学期没开始就是第 1 周 */
private fun weekOf(t: Timetable?): Int {
    if (t == null) return 1
    return TermCalendar.currentWeek(t.startDate, t.maxWeek) {
        TermCalendar.estimateStart(t.year, t.term)
    }
}

@Composable
private fun EmptyTimetable(onImport: () -> Unit) {
    val palette = LocalGlassPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        LiquidGlass(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            blurRadius = 20.dp,
            shadow = 6.dp,
            contentPadding = PaddingValues(20.dp),
        ) {
            Column {
                Text(
                    text = "还没有课表",
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "填学校教务系统的地址和账号，直接从正方抓这学期的课表。\n" +
                        "只在你点「导入」的时候联网，抓完就存本地，平时不连。",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        GlassButton(text = "导入课表", onClick = onImport, modifier = Modifier.fillMaxWidth())
    }
}

/** 时间戳转 HH:mm；0 或异常返回空串 */
private fun clockOf(millis: Long): String {
    if (millis <= 0L) return ""
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(java.util.Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

@Composable
private fun WeekHeader(
    timetable: Timetable,
    week: Int,
    syncOk: Boolean?,
    lastSyncAt: Long,
    onWeekChange: (Int) -> Unit,
    onReimport: () -> Unit,
    onCalibrate: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val start = TermCalendar.weekStart(timetable.startDate, week) {
        TermCalendar.estimateStart(timetable.year, timetable.term)
    }
    val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 6) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = termLabel(timetable.year, timetable.term),
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append("第 $week 周 · ")
                        append(TermCalendar.monthDay(start))
                        append(" - ")
                        append(TermCalendar.monthDay(end))
                    },
                    color = palette.textTertiary,
                    fontSize = 11.sp,
                )
                if (syncOk != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (syncOk) {
                            val hhmm = remember(lastSyncAt) { clockOf(lastSyncAt) }
                            if (hhmm.isBlank()) "课表已更新" else "课表已更新 · $hhmm"
                        } else {
                            "未更新成功 · 点「重新导入」重试"
                        },
                        color = if (syncOk) palette.success else palette.warn,
                        fontSize = 10.5.sp,
                    )
                }
            }
            GlassPillButton(text = "校准", onClick = onCalibrate)
            Spacer(Modifier.width(8.dp))
            GlassPillButton(text = "重新导入", onClick = onReimport)
        }

        Spacer(Modifier.height(10.dp))

        // 周次条：选中周变化时自动把它滚到可见位置
        val chipScroll = rememberScrollState()
        val density = LocalDensity.current
        LaunchedEffect(week) {
            val chipPx = with(density) { 50.dp.toPx() }
            val target = ((week - 2).coerceAtLeast(0)) * chipPx
            chipScroll.animateScrollTo(target.toInt())
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (w in 1..timetable.maxWeek) {
                val selected = w == week
                Box(
                    modifier = Modifier
                        .size(width = 42.dp, height = 30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            if (selected) palette.accent
                            else Color.White.copy(alpha = if (palette.isDark) 0.10f else 0.55f)
                        )
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onWeekChange(w) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$w",
                        color = if (selected) palette.onAccent else palette.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassPillButton(text: String, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = if (palette.isDark) 0.14f else 0.62f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = palette.textSecondary, fontSize = 11.sp)
    }
}

private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
/** 兜底 12 节；学校排到 13、14 节时按数据往上顶（见 TimetableGrid 的 maxSection） */
private const val MIN_SECTION_ROWS = 12
private val CELL_HEIGHT: Dp = 56.dp
private val SECTION_COL_WIDTH: Dp = 58.dp

@Composable
private fun TimetableGrid(
    timetable: Timetable,
    week: Int,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // 设置里改过上课时间后 revision 会 +1，读一下就能触发重组
    SectionTimesStore.revision
    val courses = timetable.grid(week)
    // 有的学校一天排到 13、14 节，写死 12 会把最后几节画到容器外面裁掉
    val maxSection = maxOf(MIN_SECTION_ROWS, courses.maxOfOrNull { it.endSection } ?: MIN_SECTION_ROWS)
    val weekStart = TermCalendar.weekStart(timetable.startDate, week) {
        TermCalendar.estimateStart(timetable.year, timetable.term)
    }
    val todayWeek = weekOf(timetable)
    val todayIndex = TermCalendar.dayOfWeekIndex()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // 星期表头：下面是这一周对应的日期
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(SECTION_COL_WIDTH))
            DAY_LABELS.forEachIndexed { index, label ->
                val date = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, index) }
                val isToday = week == todayWeek && todayIndex == index + 1
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        color = if (isToday) palette.accent else palette.textTertiary,
                        fontSize = 11.sp,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = TermCalendar.monthDay(date),
                        color = if (isToday) palette.accent else palette.textTertiary.copy(alpha = 0.75f),
                        fontSize = 8.5.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CELL_HEIGHT * maxSection),
        ) {
            // 节次列 + 背景网格
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(SECTION_COL_WIDTH)) {
                    for (s in 1..maxSection) {
                        val clock = if (s <= SectionTimesStore.SECTIONS) {
                            SectionTimesStore.start(context, s)
                        } else {
                            ""
                        }
                        Box(
                            Modifier
                                .height(CELL_HEIGHT)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$s",
                                    color = palette.textTertiary,
                                    fontSize = 10.sp,
                                )
                                if (clock.isNotEmpty()) {
                                    Text(
                                        text = clock,
                                        color = palette.textTertiary.copy(alpha = 0.7f),
                                        fontSize = 8.sp,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.weight(1f)) {
                    repeat(7) {
                        Box(Modifier.weight(1f).fillMaxSize())
                    }
                }
            }

            // 课程块：靠 offset 绝对定位，跨节自动拉高
            if (courses.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        text = "这周没有课",
                        color = palette.textTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 40.dp),
                    )
                }
            }
            CourseLayer(
                courses = courses,
                week = week,
                onClick = onCourseClick,
            )
        }
    }
}

@Composable
private fun CourseLayer(
    courses: List<Course>,
    week: Int,
    onClick: (Course) -> Unit,
) {
    val palette = LocalGlassPalette.current
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = SECTION_COL_WIDTH)
    ) {
        val cellW = maxWidth / 7
        courses.forEach { course ->
            val color = colorFor(course.name)
            val top = CELL_HEIGHT * (course.startSection - 1)
            val h = CELL_HEIGHT * course.sectionSpan

            Box(
                modifier = Modifier
                    .offset(x = cellW * (course.day - 1), y = top)
                    .width(cellW)
                    .height(h)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = if (palette.isDark) 0.42f else 0.20f))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onClick(course) },
                    )
                    .padding(horizontal = 3.dp, vertical = 4.dp),
            ) {
                Column {
                    Text(
                        text = course.name,
                        color = if (palette.isDark) Color.White else color.copy(alpha = 0.95f),
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (course.sectionSpan >= 3) 4 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (course.sectionSpan >= 2 && course.room.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = course.room,
                            color = palette.textTertiary,
                            fontSize = 8.sp,
                            lineHeight = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseDetailDialog(course: Course, week: Int, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalGlassPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.padding(horizontal = 28.dp)) {
            SolidGlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = course.name,
                        color = palette.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    DetailRow("教师", course.teacher.ifBlank { "—" })
                    DetailRow("地点", course.room.ifBlank { "—" })
                    DetailRow(
                        "时间",
                        buildString {
                            append("周${DAY_LABELS.getOrElse(course.day - 1) { "?" }} 第 ${course.startSection}-${course.endSection} 节")
                            val clock = SectionTimesStore.range(ctx, course.startSection, course.endSection)
                            if (clock.isNotBlank()) append(" · $clock")
                        },
                    )
                    DetailRow("周次", weeksText(course.weeks))
                    if (course.campus.isNotBlank()) DetailRow("校区", course.campus)
                    Spacer(Modifier.height(16.dp))
                    GlassButton(
                        text = "知道了",
                        kind = GlassButtonKind.Ghost,
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        height = 44.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekCalibrateDialog(
    currentWeek: Int,
    maxWeek: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val palette = LocalGlassPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.padding(horizontal = 28.dp)) {
            SolidGlassPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = "今天这周是第几周？",
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "教务系统不给开学日期，所以这里定一次就好。选完之后每次打开会自动落在当前周，表头也会显示具体日期。",
                        color = palette.textTertiary,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 210.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (w in 1..maxWeek) {
                            val selected = w == currentWeek
                            Box(
                                modifier = Modifier
                                    .size(width = 42.dp, height = 30.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(
                                        if (selected) palette.accent
                                        else Color.White.copy(alpha = if (palette.isDark) 0.10f else 0.55f)
                                    )
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onPick(w) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$w",
                                    color = if (selected) palette.onAccent else palette.textSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    GlassButton(
                        text = "取消",
                        kind = GlassButtonKind.Ghost,
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        height = 44.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val palette = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = palette.textTertiary, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        Text(value, color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

/** 把周次集合压回 `1-8,10-16` 这种紧凑写法 */
internal fun weeksText(weeks: Set<Int>): String {
    if (weeks.isEmpty()) return "—"
    val sorted = weeks.sorted()
    val parts = ArrayList<String>()
    var start = sorted.first()
    var prev = start
    for (i in 1 until sorted.size) {
        val cur = sorted[i]
        if (cur == prev + 1) {
            prev = cur
        } else {
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = cur
            prev = cur
        }
    }
    parts.add(if (start == prev) "$start" else "$start-$prev")
    return parts.joinToString(",") + " 周"
}
