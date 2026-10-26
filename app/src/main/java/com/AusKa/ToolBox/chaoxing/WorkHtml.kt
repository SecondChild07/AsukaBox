package com.AusKa.ToolBox.chaoxing

/**
 * 作业 / 考试列表 HTML 的解析器。
 *
 * 真实结构（2026-10 从线上抓的作业列表页 dump 出来的，见 `mooc2/work/list`）：
 *
 * ```html
 * <div class="bottomList">
 *   <ul>
 *     <li onclick="goTask(this);" data="https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?...&workId=57047584&enc=..."
 *         aria-label="四级听力 Unit 4 ; 已完成">
 *       <div class="right-content">
 *         <p class="overHidden2 fl">四级听力 Unit 4</p>
 *         <p class="status fl">已完成</p>
 * ```
 *
 * 三个容易踩空的点：
 *  1. 任务地址在 **`data` 属性**里，不是 `href`（`<a>` 只有「作答记录 / 智能分析」这种旁路链接）；
 *  2. 标题在 `p.overHidden2`，状态在 `p.status`，两者都在 `li` 里；
 *  3. 「待批阅 / 待互评」也算**已交**，只有「未交 / 未提交 / 未完成」才是待完成 ——
 *     判不准就给 Unknown（界面显示「待确认」），不猜成待完成。
 */
internal object WorkHtml {

    private val BLOCK_SPLIT = Regex("(?i)<li\\b")
    private val SCRIPT = Regex("(?i)<script[\\s\\S]*?</script>")
    private val STYLE = Regex("(?i)<style[\\s\\S]*?</style>")
    private val COMMENT = Regex("(?i)<!--[\\s\\S]*?-->")
    private val DATA_ATTR = Regex("(?is)\\bdata\\s*=\\s*\"(https?://[^\"]+)\"")
    private val ANCHOR = Regex("(?is)<a\\b([^>]*)>(.*?)</a>")
    private val HREF = Regex("(?is)href\\s*=\\s*\"([^\"]*)\"")
    private val TITLE_ATTR = Regex("(?is)title\\s*=\\s*\"([^\"]*)\"")
    private val ARIA = Regex("(?is)aria-label\\s*=\\s*\"([^\"]*)\"")
    private val P_TITLE = Regex("(?is)<p[^>]*class=\"[^\"]*overHidden2[^\"]*\"[^>]*>(.*?)</p>")
    private val P_STATUS = Regex("(?is)<p[^>]*class=\"[^\"]*status[^\"]*\"[^>]*>(.*?)</p>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val SPACES = Regex("[\\s\\u00a0]+")
    private val DATE = Regex(
        "(\\d{4}-\\d{1,2}-\\d{1,2}(?:\\s+\\d{1,2}:\\d{2})?|\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2})"
    )
    /** 真正的任务入口：作业/考试的作答页。别把「申诉」「帮助」这类旁路链接当成任务 */
    private val TASK_URL = Regex(
        "(?i)(workId=|examId=|/work/task|/exam/preview|/exam/test|doWork|studentstudy)"
    )

    /** 已交：含「待批阅 / 待互评」——交了就算完成，只是老师还没批 */
    private val DONE_WORDS = listOf(
        "已完成", "已提交", "待批阅", "已批阅", "待互评", "已互评", "已通过", "已交", "已阅",
    )
    private val EXPIRED_WORDS = listOf("已过期", "已截止", "逾期", "已结束", "已关闭", "已撤销")
    private val TODO_WORDS = listOf(
        "未交", "未提交", "待提交", "未完成", "去完成", "立即完成", "开始答题", "做作业", "未开始",
    )
    private val NOISE_TITLES = setOf("作业", "考试", "查看", "查看更多", "详情", "进入", "去完成", "立即完成")

    /** 页面是不是被踢回登录页了 —— 这种情况不该当成「0 条作业」 */
    fun looksLikeLogin(html: String): Boolean {
        if (html.isBlank()) return true
        val head = html.take(4000)
        return head.contains("<title>用户登录</title>") || head.contains("passport2.chaoxing.com/login")
    }

    /** 列表 HTML → 任务行。course 用来标注归属，exam 表示这次解析的是考试页 */
    fun parse(html: String, course: String, exam: Boolean): List<ChaoxingTask> {
        if (html.isBlank()) return emptyList()
        val clean = stripNoise(html)
        // 没有状态列（p.status）就说明这页压根不是任务列表（比如「暂无考试」的空页），
        // 空页里还散着「申诉」「帮助」之类的链接，硬解析就会凭空造出任务。
        if (!P_STATUS.containsMatchIn(clean)) return emptyList()
        val blocks = splitBlocks(clean)
        val out = ArrayList<ChaoxingTask>()
        val seen = HashSet<String>()
        for (block in blocks) {
            val task = toTask(block, course, exam) ?: continue
            if (seen.add(task.key)) out.add(task)
            if (out.size >= 80) break
        }
        return out
    }

    private fun toTask(block: String, course: String, exam: Boolean): ChaoxingTask? {
        val url = taskUrl(block) ?: return null
        val text = textOf(block)
        var title = P_TITLE.find(block)?.groupValues?.get(1)?.let { textOf(it) }.orEmpty()
        if (title.isEmpty()) {
            // aria-label 形如「四级听力 Unit 4 ; 已完成」——分号前面是标题
            val aria = ARIA.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (aria.isNotEmpty()) title = aria.split(";", "；").first().trim()
        }
        if (title.isEmpty()) {
            title = ANCHOR.find(block)?.groupValues?.get(2)?.let { textOf(it) }.orEmpty()
        }
        if (title.length < 2 || NOISE_TITLES.contains(title)) title = text.take(40)
        if (title.length < 2) return null

        val statusText = P_STATUS.find(block)?.groupValues?.get(1)?.let { textOf(it) }.orEmpty()
        val due = DATE.find(text)?.value ?: ""
        return ChaoxingTask(
            course = course.ifEmpty { "未标注课程" },
            title = title.take(60),
            status = statusOf(if (statusText.isNotEmpty()) statusText else text),
            url = url,
            due = due.take(20),
            exam = exam || url.contains("exam") || title.contains("考试") || title.contains("测试"),
        )
    }

    /** 任务入口地址：优先 `data` 属性（作业页的真实结构），退回 href */
    private fun taskUrl(block: String): String? {
        DATA_ATTR.find(block)?.groupValues?.get(1)?.let { url ->
            if (TASK_URL.containsMatchIn(url)) return url
        }
        ANCHOR.findAll(block).forEach { m ->
            val href = HREF.find(m.groupValues[1])?.groupValues?.get(1)?.trim().orEmpty()
            if (href.isEmpty()) return@forEach
            if (TASK_URL.containsMatchIn(href)) return href
        }
        return null
    }

    private fun splitBlocks(html: String): List<String> {
        val parts = BLOCK_SPLIT.split(html)
        // 第一段是第一个 <li 之前的页面头部，后面每段开头都被切掉了 "<li"
        return if (parts.size > 1) parts.drop(1) else ANCHOR.findAll(html).map { it.value }.toList()
    }

    private fun statusOf(text: String): TaskStatus {
        if (EXPIRED_WORDS.any { text.contains(it) }) return TaskStatus.Expired
        if (DONE_WORDS.any { text.contains(it) }) return TaskStatus.Done
        if (TODO_WORDS.any { text.contains(it) }) return TaskStatus.Todo
        return TaskStatus.Unknown
    }

    private fun stripNoise(html: String): String =
        html.replace(SCRIPT, " ").replace(STYLE, " ").replace(COMMENT, " ")

    private fun textOf(fragment: String): String {
        var s = fragment.replace(TAG, " ")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return SPACES.replace(s, " ").trim()
    }
}
