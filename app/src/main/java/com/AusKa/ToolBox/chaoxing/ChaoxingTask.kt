package com.AusKa.ToolBox.chaoxing

import org.json.JSONArray
import org.json.JSONObject

/** 一条作业/考试任务。 */
internal data class ChaoxingTask(
    val course: String,
    val title: String,
    val status: TaskStatus,
    val url: String,
    val due: String = "",
    val exam: Boolean = false,
) {
    val key: String get() = url.ifEmpty { course + "/" + title + "/" + exam }
}

internal enum class TaskStatus {
    Todo,
    Done,
    Expired,
    Unknown;

    val label: String
        get() = when (this) {
            Todo -> "待完成"
            Done -> "已完成"
            Expired -> "已截止"
            Unknown -> "待确认"
        }
}

/**
 * 扫描队列里的一门课：courseId / classId / cpi 是打开课程页要用的三个参数。
 *
 * cpi 是超星跟着用户走的班级个人标识，课程列表里每门课的链接都带着，
 * 少了它课程页会 400。
 */
internal data class ScanCourse(
    val courseId: String,
    val classId: String,
    val name: String,
    val cpi: String = "",
) {
    val key: String get() = courseId + "|" + classId
}

/** 学习通各页面的地址模板，集中放一处方便随时改 */
internal object ChaoxingUrls {

    /** 「我的课程」列表页，登录后正常都能打开 */
    const val COURSE_LIST = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction"

    /**
     * 课程主页（任务/章节/作业/考试 那些 tab 都在这一页）。
     *
     * 打开后会自动跳到 mooc2-ans 的 mycourse/stu，跳完 URL 里会带一段新的 enc/t，
     * 所以这个地址本身不用带签名。
     */
    fun middle(c: ScanCourse): String =
        "https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${c.courseId}" +
            "&clazzid=${c.classId}" +
            (if (c.cpi.isEmpty()) "" else "&cpi=${c.cpi}") +
            "&ismooc2=1&v=2"

    /** 作业列表（缺 enc/cpi/stuenc 会被判「无权限的操作」，只用作回退） */
    fun workList(c: ScanCourse): String =
        "https://mooc1.chaoxing.com/mooc2/work/list?courseId=${c.courseId}&classId=${c.classId}"

    fun examList(c: ScanCourse): String =
        "https://mooc1.chaoxing.com/mooc2/exam/exam-list?courseId=${c.courseId}&classId=${c.classId}"
}

/**
 * 把页面里扫回来的 JSON 转成结构化数据。
 *
 * 学习通没有统一的作业列表接口，每门课、每个学院模板的 DOM 都不一样，
 * 所以页面脚本只负责「找出像作业的行 + 读它附近的状态字样」，
 * 归一化、去重、分类都留在这里 —— 出问题好在单测里复现。
 */
internal object TaskScan {

    /** 「扫描本页」用：当前页面上的作业入口 */
    fun parse(raw: String): List<ChaoxingTask> = parseRows(raw, "", exam = false)

    /** 课程列表页：取出 courseId / classId，用来拼后面的作业、考试地址 */
    fun parseCourses(raw: String): List<ScanCourse> {
        val root = json(raw) ?: return emptyList()
        if (root.optString("t") != "courses") return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<ScanCourse>(items.length())
        val seen = HashSet<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val courseId = item.optString("courseId").trim()
            val classId = item.optString("classId").trim()
            if (courseId.isEmpty() || classId.isEmpty()) continue
            val course = ScanCourse(
                courseId = courseId,
                classId = classId,
                name = item.optString("course").trim().ifEmpty { "未命名课程" },
                cpi = item.optString("cpi").trim(),
            )
            if (seen.add(course.key)) out.add(course)
        }
        return out
    }

    /** 作业页 / 考试页：一行一条记录 */
    fun parseRows(raw: String, course: String, exam: Boolean): List<ChaoxingTask> {
        val root = json(raw) ?: return emptyList()
        val kind = root.optString("t")
        if (kind != "rows" && kind != "tasks") return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<ChaoxingTask>(items.length())
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            if (title.isEmpty()) continue
            out.add(
                ChaoxingTask(
                    course = course.ifEmpty { item.optString("course").trim() }.ifEmpty { "未标注课程" },
                    title = title.take(60),
                    status = statusOf(item.optString("status")),
                    url = item.optString("url").trim(),
                    due = item.optString("due").trim().take(20),
                    exam = exam || item.optString("kind") == "exam",
                )
            )
        }
        return out
    }

    /** 按 key 去重合并，后来的覆盖先来的（页面上状态可能变过） */
    fun merge(current: List<ChaoxingTask>, more: List<ChaoxingTask>): List<ChaoxingTask> {
        if (more.isEmpty()) return current
        val map = LinkedHashMap<String, ChaoxingTask>(current.size + more.size)
        current.forEach { map[it.key] = it }
        more.forEach { map[it.key] = it }
        return map.values.toList()
    }

    /** 待完成（含已截止、待确认）：这些是还需要动手的 */
    fun open(tasks: List<ChaoxingTask>): List<ChaoxingTask> =
        tasks.filter { it.status != TaskStatus.Done }

    /** 已完成：交过 / 批阅过 */
    fun done(tasks: List<ChaoxingTask>): List<ChaoxingTask> =
        tasks.filter { it.status == TaskStatus.Done }

    fun todoCount(tasks: List<ChaoxingTask>): Int = tasks.count { it.status == TaskStatus.Todo }

    fun expiredCount(tasks: List<ChaoxingTask>): Int = tasks.count { it.status == TaskStatus.Expired }

    fun courseCount(tasks: List<ChaoxingTask>): Int = tasks.map { it.course }.distinct().size

    private fun statusOf(raw: String): TaskStatus = when (raw.trim().lowercase()) {
        "done" -> TaskStatus.Done
        "expired" -> TaskStatus.Expired
        "todo" -> TaskStatus.Todo
        else -> TaskStatus.Unknown
    }

    private fun json(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 把上一次扫到的清单落盘，下次打开 App 直接先显示出来，
 * 后台再同步一次覆盖 —— 不然每次进来都要干等两分钟。
 */
internal object TaskCache {

    fun encode(tasks: List<ChaoxingTask>): String {
        if (tasks.isEmpty()) return ""
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject()
                    .put("course", task.course)
                    .put("title", task.title)
                    .put("status", statusKey(task.status))
                    .put("url", task.url)
                    .put("due", task.due)
                    .put("exam", task.exam)
            )
        }
        return array.toString()
    }

    fun decode(raw: String): List<ChaoxingTask> {
        if (raw.isBlank()) return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<ChaoxingTask>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            if (title.isEmpty()) continue
            out.add(
                ChaoxingTask(
                    course = item.optString("course").trim().ifEmpty { "未标注课程" },
                    title = title,
                    status = statusOf(item.optString("status")),
                    url = item.optString("url").trim(),
                    due = item.optString("due").trim(),
                    exam = item.optBoolean("exam"),
                )
            )
        }
        return out
    }

    private fun statusKey(status: TaskStatus): String = when (status) {
        TaskStatus.Done -> "done"
        TaskStatus.Expired -> "expired"
        TaskStatus.Todo -> "todo"
        TaskStatus.Unknown -> "unknown"
    }

    private fun statusOf(raw: String): TaskStatus = when (raw.trim().lowercase()) {
        "done" -> TaskStatus.Done
        "expired" -> TaskStatus.Expired
        "todo" -> TaskStatus.Todo
        else -> TaskStatus.Unknown
    }
}

/**
 * DEBUG 包里的样例任务：只在 `BuildConfig.DEBUG` 下由「样例」按钮灌进内存，
 * 用来在没有真实登录的情况下检查列表排版。release 包里这段不会被执行。
 */
internal val SAMPLE_TASKS = listOf(
    ChaoxingTask("数据结构", "第三章 树与二叉树 课后作业", TaskStatus.Todo, "", "10-30 23:59"),
    ChaoxingTask("线性代数", "第二章 矩阵运算 习题", TaskStatus.Done, "", "10-24 23:59"),
    ChaoxingTask("大学英语", "Unit 2 单元测试", TaskStatus.Expired, "", "10-20 23:59"),
    ChaoxingTask("大学物理", "期末模拟考试", TaskStatus.Todo, "", "11-12 14:00", exam = true),
)
