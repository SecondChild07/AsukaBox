package com.AusKa.ToolBox.chaoxing

/**
 * 一次作答的「轮次令牌」。
 *
 * 一份作业的作答是一条异步链：抽题 → 逐题问模型 → 回填，中间还夹着音频下载和转写。
 * 链上每一步都可能落在「用户已经换了另一份作业」之后，于是出现两种后果：
 *
 *  - 旧链算完照样回填，把上一份作业的答案按同样的题号点到新页面上（题号对得上、答案对不上）；
 *  - `solving` 一直挂着 true，新页面连自动识别都进不去。
 *
 * 用户看到的就是「退出去了还在答上一份」。所以每次开跑领一个新号，
 * 换作业 / 退出 / 手动停止就 [stop]，链上每个回调先问一句「我还是这一轮吗」。
 * 号码只增不减，不存在复用。
 */
internal class SolveGuard {

    @Volatile
    private var token = 0

    /** 本轮开跑时的地址（原样，用来撤掉「这个地址已经自动做过」的记录） */
    @Volatile
    var started: String = ""
        private set

    /** 本轮开跑时地址的归一化形式，用来判断页面有没有换 */
    @Volatile
    var page: String = ""
        private set

    /** 开跑：作废上一轮，返回本轮的号 */
    fun start(url: String): Int {
        started = url
        page = key(url)
        token += 1
        return token
    }

    /** 停止：作废所有在飞的回调 */
    fun stop() {
        token += 1
        started = ""
        page = ""
    }

    /** 这一轮还活着吗 —— 后台线程只查这个（那边读 webView.url 会崩） */
    fun alive(run: Int): Boolean = token == run

    /** 活着、而且页面还是开跑时那一页 —— 只在主线程用 */
    fun aliveOn(run: Int, currentUrl: String): Boolean = alive(run) && stillOn(currentUrl)

    /** 当前页面还是开跑时那一页吗（没记地址时一律算「是」） */
    fun stillOn(currentUrl: String): Boolean = page.isEmpty() || page == key(currentUrl)

    companion object {
        /**
         * 比地址时忽略锚点和结尾斜杠，也不区分大小写：
         * 同一页里跳锚点、滚动、超星把参数大小写混着写，都不该当成「换了作业」。
         */
        fun key(url: String): String = url.substringBefore('#').trim().trimEnd('/').lowercase()
    }
}
