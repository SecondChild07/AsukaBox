package com.AusKa.ToolBox.chaoxing

/**
 * 给模型的提示词。
 *
 * 拆成两层：
 *  - [SYSTEM] 管「怎么说话」：只输出一个 JSON，不许解释、不许 Markdown；
 *  - [user] 管「这题怎么想」：题型专属做法 + 课程上下文 + 不确定就不填。
 *
 * 第二层才是准确率的来源。同一道题，「下面是题目和选项，给答案」和
 * 「逐项判断每个选项对不对，只在确信时给出字母」在超星这种题海里差得很多。
 */
internal object QuizPrompt {

    const val SYSTEM: String =
        "你是严谨的答题助手，服务对象是大学生。输出规矩：" +
            "① 先用一句话点出判断依据（不超过 40 字），然后另起一行，只输出一个 JSON 对象；" +
            "除那一句依据外不要解释、不要 Markdown 代码块、不要分点罗列；" +
            "② 答案必须基于题目本身（题干、选项、听力原文），不要编造题目里不存在的信息；" +
            "③ 先定位关键句再下结论：干扰项常和原文用词相近但偷换主语、时间、数量或因果，" +
            "把「原文到底说了什么」和「选项说成了什么」分开比对；同义改写算匹配，程度词不一致不算；" +
            "④ 每道题都要带一个 conf 字段（0~1，表示你对这道题有多确定），" +
        "低于 0.8 就说明你在猜，同时要尽量给出你倾向的答案；" +
        "实在完全没头绪才输出空字符串；" +
            "⑤ 格式：选择题 {\"answer\":\"C\",\"conf\":0.92}（多选按字母升序拼，如 \"ACD\"）；" +
            "判断题 {\"answer\":\"对\"} 或 {\"answer\":\"错\"}（同样带 conf）；" +
            "填空题 {\"answer\":[\"第一空\",\"第二空\"]}；" +
            "排序题 {\"answer\":\"BACD\"}（按正确顺序排字母）；" +
            "连线题 {\"pairs\":[{\"left\":\"左项\",\"right\":\"右项\"}]}；" +
            "简答/论述题 {\"answer\":\"答案正文\"}。"

    private const val RULE_CHOICE =
        "做法：先找到与题干对应的那句原文，再逐项判断每个选项是否与它相符，只选最符合的一项；" +
            "注意「不正确/不属于/错误的是」这类反向问法；四个选项都「差不多」时选与原文细节完全一致的。"

    private const val RULE_MULTI =
        "做法：对每个选项单独判断对错，把所有正确的选项都选上；" +
            "多选题至少两个答案，若只有一个确定则按确定项输出。"

    private const val RULE_JUDGE =
        "做法：先找出题干里的绝对化措辞（一定、必须、全部、唯一）再判断；" +
            "答案只能是「对」或「错」。"

    private const val RULE_FILL =
        "做法：按空的顺序给出答案，每个空一个字符串；" +
            "保持原文术语，不要加序号、不要重复题干、不要写解释。"

    private const val RULE_ESSAY =
        "做法：写成可以直接交的正式答案，分点陈述，覆盖题干每个要求；" +
            "篇幅按题目要求（论述题一般 300~600 字），不要出现「作为AI」之类的话。"

    private const val RULE_SORT =
        "做法：先还原正确的逻辑顺序，再用选项字母把顺序连成一串，例如 \"BACD\"。"

    private const val RULE_CONNECT =
        "做法：把左侧每一项和右侧唯一正确的项配对；" +
            "左右项都要用题目里的原文，不要改写。"

    private const val RULE_LISTENING =
        "做法：这是听力题，先读题干和选项，再结合听力原文确定答案；" +
            "听力原文来自语音识别，可能有错字，按语义理解。"

    fun rule(kind: String): String = when (kind) {
        "choice" -> RULE_CHOICE
        "multi" -> RULE_MULTI
        "judge" -> RULE_JUDGE
        "fill" -> RULE_FILL
        "essay" -> RULE_ESSAY
        "sort" -> RULE_SORT
        "connect" -> RULE_CONNECT
        "listening" -> RULE_LISTENING
        else -> "做法：先判断题干在问什么，再给出最可能的答案；不确定就留空。"
    }

    fun label(kind: String): String = when (kind) {
        "choice" -> "单选题"
        "multi" -> "多选题"
        "judge" -> "判断题"
        "fill" -> "填空题"
        "essay" -> "简答题"
        "sort" -> "排序题"
        "connect" -> "连线题"
        "listening" -> "听力题"
        else -> "题目"
    }

    /** 单题的 user 消息 */
    fun user(q: QuizQuestion, course: String = "", transcript: String = ""): String {
        val sb = StringBuilder()
        if (course.isNotBlank()) sb.append("课程：").append(course).append('\n')
        sb.append("题型：").append(label(q.kind)).append('\n')
        sb.append(rule(q.kind)).append('\n')
        sb.append("题干：").append(q.stem).append('\n')
        if (q.options.isNotEmpty()) {
            sb.append("选项：\n")
            for (i in q.options.indices) {
                sb.append(('A' + i)).append(". ").append(q.options[i]).append('\n')
            }
        }
        if (q.inputs > 1) {
            sb.append("这道题有 ").append(q.inputs).append(" 个空，答案按顺序给出。\n")
        }
        if (transcript.isNotBlank()) {
            sb.append("听力原文：\n").append(transcript).append('\n')
        }
        sb.append("只输出 JSON。")
        return sb.toString()
    }

    /**
     * 题目被做成图片时（超星听力作业常见：Section A/B 的题干和选项都是图片）走这条。
     * 一次问一整块：模型按题号顺序返回字母数组，程序负责按空位顺序填。
     */
    /** 第一次把握不到 0.8 时，让它再想一遍（同一题、加一句提醒） */
    fun retry(q: QuizQuestion, course: String = "", transcript: String = ""): String =
        user(q, course, transcript) + "\n\n刚才那次你自己觉得不太确定（conf<0.8）。" +
            "这次换个角度：先把题干的关键词圈出来，再逐项对照原文，重新判断一遍。" +
            "仍然要带 conf，还是不确定就照实写低的 conf。" +
            "输出格式不变，只输出 JSON。"

    fun vision(course: String, blankCount: Int, transcript: String, stem: String = ""): String {
        val sb = StringBuilder()
        if (course.isNotBlank()) sb.append("课程：").append(course).append('\n')
        sb.append("题型：听力选择题（题目在下面提供的图片里，图片按顺序对应第 1 题到第 ")
            .append(blankCount).append(" 题）\n")
        sb.append(
            "做法：先读图上的题干和 A/B/C/D 选项（图片可能是分栏排版，按题号 1、2、3… 的顺序读，" +
                "不要把相邻题的选项混在一起），再拿听力原文里的关键句逐题比对；" +
                "干扰项常和原文用词相近但偷换主语/时间/数量，只有细节也吻合的才算对；" +
                "每题只回一个字母；判断不出来就留空字符串。\n",
        )
        if (stem.isNotBlank()) sb.append("页面文字：").append(stem).append('\n')
        if (transcript.isNotBlank()) {
            sb.append("听力原文：\n").append(transcript).append('\n')
        }
        sb.append("输出格式：{\"answers\":[\"C\",\"A\",\"B\",…]}，共 ")
            .append(blankCount).append(" 项。只输出 JSON。")
        return sb.toString()
    }
}
