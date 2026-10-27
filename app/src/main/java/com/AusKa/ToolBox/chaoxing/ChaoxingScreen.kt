package com.AusKa.ToolBox.chaoxing

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.AusKa.ToolBox.BuildConfig
import com.AusKa.ToolBox.data.ChaoxingStore
import com.AusKa.ToolBox.glass.GlassButton
import com.AusKa.ToolBox.glass.GlassButtonKind
import com.AusKa.ToolBox.glass.GlassLabel
import com.AusKa.ToolBox.glass.GlassPalette
import com.AusKa.ToolBox.glass.GlassTextField
import com.AusKa.ToolBox.glass.LiquidGlass
import com.AusKa.ToolBox.glass.LocalGlassPalette
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val PAGE_HOME = "home"
private const val PAGE_WEB = "web"

private const val SCAN_IDLE = 0
private const val SCAN_COURSES = 1
private const val SCAN_COLLECT = 2

/** 并发抓几门课：太大容易被服务端限速，5 路在实测里最稳也够快 */
private const val PARALLEL_COURSE = 3

/** 每门课之间错开的毫秒数，太快会被风控当成机器人 */
private const val STAGGER_MS = 320L

/**
 * 学习通助手。
 *
 * 两页结构：
 *  - 首页：任务看板（待完成 / 已完成分类）+ 提交策略 + AI Key；
 *  - 网页：学习通本体，桌面 UA + 注入脚本，登录和扫描都在这里跑。
 *
 * 登录成功（拿到 _uid / fid 之类的会话项）后自动扫一遍。扫描不是「打开作业页读 DOM」——
 * 作业列表在跨域 iframe 里，JS 进不去；而是「开课程页 → 点作业/考试 tab → 读出 iframe 上那个
 * 已经签好名的地址（enc / cpi / stuenc / t 都在里面）→ 用同一套 Cookie 从原生侧拉 HTML」。
 * 全程只读，不点提交、不写输入框；回填是阶段 3、提交是阶段 4 的事。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ChaoxingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = LocalGlassPalette.current

    var page by rememberSaveable { mutableStateOf(PAGE_HOME) }
    var snapshot by remember { mutableStateOf(ChaoxingStore.cookieSnapshot(context)) }
    var savedAt by remember { mutableStateOf(ChaoxingStore.savedAt(context)) }
    var probeText by remember { mutableStateOf("") }
    var frameSrc by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf(emptyList<ChaoxingTask>()) }
    // 本次扫描新抓到的（与上一轮的 tasks 分开，扫完再整体替换）
    var scanned by remember { mutableStateOf(emptyList<ChaoxingTask>()) }
    var scanNote by remember { mutableStateOf("") }
    var urlText by rememberSaveable { mutableStateOf(ChaoxingScripts.LOGIN_URL) }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var submitMode by remember { mutableStateOf(ChaoxingStore.submitMode(context)) }
    var hasKey by remember { mutableStateOf(ChaoxingStore.aiKey(context).isNotEmpty()) }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var modelDraft by rememberSaveable { mutableStateOf(ChaoxingStore.aiModel(context)) }

    // 自动扫描的进度状态
    var scanning by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(SCAN_IDLE) }
    var courses by remember { mutableStateOf(emptyList<ScanCourse>()) }
    var cursor by remember { mutableStateOf(0) }
    var courseTries by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf("") }
    var scanLog by remember { mutableStateOf(emptyList<String>()) }
    var autoScanTried by remember { mutableStateOf(false) }
    // 这一轮还有几门课没抓完（原生并发抓取，全部抓完才算扫完）
    var pending by remember { mutableStateOf(0) }
    // 这一轮被学习通的风控验证码拦下过（【9010】操作异常）
    var captcha by remember { mutableStateOf(false) }
    // 从清单点进去看题时，网页页顶部显示的那条任务标题
    var openTitle by remember { mutableStateOf("") }
    // 课程名单独存一份：标题是作业名（「第一章」），喂给模型当课程上下文会误导它
    var openCourse by remember { mutableStateOf("") }
    // 自动做过题的地址，避免 onPageFinished 多次回调把同一页反复刷
    var autoSolved by remember { mutableStateOf(emptySet<String>()) }

    // AI 答题的进行状态（只回填，不提交）
    var solving by remember { mutableStateOf(false) }
    var solveNote by remember { mutableStateOf("") }
    // 接口不支持图片输入时弹一次，别让它悄悄答错

    val signedIn = remember(snapshot) { CookieSnapshot.looksSignedIn(snapshot) }
    val webView = remember(context) { WebView(context) }

    fun rememberSession() {
        val raw = readCookies()
        // 只有真的拿到会话项（_uid / UID / fid）才落盘，否则登录页也会被当成「已保存的会话」
        if (!CookieSnapshot.looksSignedIn(raw)) return
        snapshot = raw
        savedAt = System.currentTimeMillis()
        ChaoxingStore.saveCookieSnapshot(context, raw)
    }

    fun restoreSession() {
        val raw = ChaoxingStore.cookieSnapshot(context)
        if (raw.isBlank()) return
        val manager = CookieManager.getInstance()
        CookieSnapshot.decode(raw).forEach { (host, cookie) ->
            cookie.split(";").forEach { piece ->
                val kv = piece.trim()
                if (kv.isNotEmpty()) manager.setCookie("https://$host", kv)
            }
        }
        manager.flush()
    }

    /**
     * 退出学习通登录。
     *
     * 三件事一起做：清 WebView 的 cookie（不然网页里还是登录态）、清本机存的会话快照、
     * 清掉上一次扫出来的清单（那是上一个人的作业，留着容易看错）。
     */
    fun logout() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        ChaoxingStore.clearSession(context)
        ChaoxingStore.setTasksJson(context, "")
        snapshot = ""
        savedAt = 0L
        tasks = emptyList()
        scanned = emptyList()
        courses = emptyList()
        cursor = 0
        scanning = false
        stage = SCAN_IDLE
        progress = ""
        autoScanTried = false
        autoSolved = emptySet()
        setupOpen = false
        scanNote = "已退出登录，下次进来重新登一次"
        page = PAGE_HOME
        urlText = ChaoxingScripts.LOGIN_URL
    }

    fun runProbe() {
        webView.evaluateJavascript(ChaoxingScripts.PROBE) { raw ->
            val json = unquote(raw)
            if (BuildConfig.DEBUG) android.util.Log.d("CSProbe", json)
            val info = parseProbe(json)
            probeText = info.text
            frameSrc = info.frame
        }
    }

    fun scanPageOnly() {
        webView.evaluateJavascript(ChaoxingScripts.SCAN_TASKS) { raw ->
            val found = TaskScan.parse(unquote(raw))
            tasks = TaskScan.merge(tasks, found)
            scanNote = if (found.isEmpty()) {
                "这一页没找到作业入口，先进到具体的课里再扫"
            } else {
                "本页扫到 " + found.size + " 条 · 待完成 " + TaskScan.open(tasks).size
            }
        }
    }

    fun finishScan(note: String) {
        scanning = false
        stage = SCAN_IDLE
        progress = ""
        page = PAGE_HOME
        // 先记住这一轮有没有被风控拦过，再复位
        val blocked = captcha
        captcha = false
        // 被风控拦下时，别把「没读到」说成「没有作业」
        scanNote = if (blocked && scanned.isEmpty()) {
            "学习通要验证码了 · 到网页里过一次再重新同步"
        } else {
            note
        }
        // 这一轮抓到了东西就以它为准（旧的可能是过期状态）；
        // 被风控打断时只合并，别把上次那份完整清单删成残缺的
        if (blocked) {
            tasks = TaskScan.merge(tasks, scanned)
        } else if (scanned.isNotEmpty()) {
            tasks = scanned
        }
        // 结果落盘：下次打开先看旧的，后台再刷新
        ChaoxingStore.setTasksJson(context, TaskCache.encode(tasks))
    }

    /**
     * 所有课程都抓完（pending 归零）才算扫完。
     *
     * 现在每门课是「课程页 → 作业列表 + 考试列表」三条请求并发在跑，
     * 完成顺序跟课程顺序无关，所以只能靠计数器收工。
     */
    fun maybeFinish() {
        if (!scanning) return
        if (pending > 0) return
        val total = if (scanned.isNotEmpty()) scanned.size else tasks.size
        finishScan(
            if (total == 0) {
                "扫完 " + courses.size + " 门课，没读到作业（这门课可能真没有）"
            } else {
                "扫完 " + courses.size + " 门课 · 共 " + total + " 条"
            }
        )
    }

    /** 点开某一条任务：直接把它对应的页面装进后台 WebView，再切到网页页看题 */
    fun openTask(task: ChaoxingTask) {
        val url = task.url
        if (url.isBlank()) {
            scanNote = "这一条没拿到地址，先重新同步一次"
            return
        }
        scanning = false
        stage = SCAN_IDLE
        progress = ""
        openTitle = task.title
        openCourse = task.course
        urlText = url
        page = PAGE_WEB
        webView.loadUrl(url)
    }

    // ---------------------------------------------------------------- AI 做题（只填不交）

    fun kindLabel(kind: String): String = QuizPrompt.label(kind)

    /** 给模型的课程上下文：优先用课程名，没拿到才退回作业标题 */
    fun courseContext(): String = openCourse.ifBlank { openTitle }

    fun promptFor(q: QuizQuestion): String = QuizPrompt.user(q, courseContext())

    /**
     * 把当前页面的题目交给模型，逐题回填。
     *
     * 硬规矩：只填，不点提交。填完再回读一遍，把「填了哪些」告诉用户，让用户自己检查。
     */
    fun solvePage() {
        if (solving) return
        val key = ChaoxingStore.aiKey(context)
        if (key.isBlank()) {
            solveNote = "先去「设置 → AI Key」填一条 key"
            return
        }
        // 可能被开头那次「接口自检」改掉（模型名过期时），所以用 var
        var model = ChaoxingStore.aiModel(context).ifBlank { AiClient.DEFAULT_MODEL }
        var apiChecked = false
        // 地址优先用用户填的；没填就按模型名推（deepseek-chat → api.deepseek.com/v1 这种）
        val apiBase = ChaoxingStore.aiBase(context).ifBlank {
            AiEndpoints.baseFor(model) ?: AiClient.DEFAULT_BASE
        }
        // 一套模型同时做文字题和图片题。不按模型名预判能不能识图 —— 用户明确说过他的模型支持，
        // 少一次「先猜再跳过」的往返；真失败就留空并记进错误里，不再弹窗拦整页
        solving = true
        solveNote = "读取题目…"
        webView.evaluateJavascript(QuizScripts.QUESTIONS) { raw ->
            val list = QuizParse.questions(unquote(raw))
            if (list.isEmpty()) {
                solving = false
                solveNote = "这一页没抽到题目（要进到作答页，查看页没有选项）"
                return@evaluateJavascript
            }
            val stats = intArrayOf(0, 0, list.size)
            // 本来就有答案、没动的题数
            var already = 0
            // 改掉之前错选的题数
            var overwritten = 0
            // 模型自己没把握、留给用户看的题数
            var uncertain = 0
            // 题库直接命中的题数（命中就不问模型）
            var fromBank = 0
            // 听力题没拿到转写（服务商没有语音转文字接口）的题数
            var noTranscript = 0
            // 所有线程共用：记下前几条失败原因，结束时直接显示，避免「0 题」但看不出为什么
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            val hasAudio = list.any { it.audio.isNotBlank() || it.kind == "listening" }
            if (hasAudio) {
                // 听力题先把播放倍速拨到 2 倍，帮用户省一半时间（页面允许的话）
                webView.evaluateJavascript(QuizScripts.speedJs(2.0), null)
            }
            solveNote = if (hasAudio) "共 " + list.size + " 题 · 听力 2 倍速 · 开始填…" else "共 " + list.size + " 题 · 开始填…"

            var stepRef: ((Int) -> Unit)? = null

            fun applyText(q: QuizQuestion, parts: List<String>, at: Int, slot: Int) {
                if (slot >= parts.size) {
                    stats[0]++
                    stepRef?.invoke(at + 1)
                    return
                }
                val js = if (q.kind == "essay") {
                    QuizScripts.essayJs(q.index, parts[0])
                } else {
                    QuizScripts.fillJs(q.index, slot, parts[slot])
                }
                webView.evaluateJavascript(js) { result ->
                    val ok = unquote(result).contains("\"ok\":true")
                    val last = q.kind == "essay" || slot + 1 >= parts.size
                    if (last) {
                        if (ok) stats[0]++ else stats[1]++
                        stepRef?.invoke(at + 1)
                    } else {
                        applyText(q, parts, at, slot + 1)
                    }
                }
            }

            fun applyAt(q: QuizQuestion, ans: QuizAnswer, at: Int, prev: List<String>, prevFilled: Int) {
                // 模型常把「对/错」或某个选项原文当答案给回来，先对齐成页面上的字母
                val letters = AnswerResolver.letters(q, ans)
                val texts = AnswerResolver.texts(q, ans)
                if (letters.isNotEmpty()) {
                    val want = letters.uppercase().toList().sorted()
                    val have = prev.map { it.uppercase() }.sorted()
                    if (have.isNotEmpty() && have == want) {
                        // 已经选的就是这个：再点一次反而会取消，直接算过
                        already++
                        stats[0]++
                        stepRef?.invoke(at + 1)
                        return
                    }
                    if (have.isNotEmpty() && ans.conf < 0.9) {
                        // 已有别的答案、模型自己也不够确定：不擅自改，留给用户看
                        uncertain++
                        stats[1]++
                        stepRef?.invoke(at + 1)
                        return
                    }
                    val click = if (q.kind == "sort") {
                        QuizScripts.sortJs(q.index, letters)
                    } else {
                        QuizScripts.chooseJs(q.index, letters)
                    }
                    if (have.isEmpty()) {
                        webView.evaluateJavascript(click) { result ->
                            if (unquote(result).contains("\"ok\":true")) stats[0]++ else stats[1]++
                            stepRef?.invoke(at + 1)
                        }
                    } else {
                        // 先取消旧的选择再点新的：多选题不清会越点越多
                        webView.evaluateJavascript(QuizExtra.clearJs(q.index)) {
                            webView.evaluateJavascript(click) { result ->
                                if (unquote(result).contains("\"ok\":true")) {
                                    stats[0]++
                                    overwritten++
                                } else {
                                    stats[1]++
                                }
                                stepRef?.invoke(at + 1)
                            }
                        }
                    }
                    return
                }
                if (texts.isNotEmpty()) {
                    if (prevFilled > 0) {
                        already++
                        stats[0]++
                        stepRef?.invoke(at + 1)
                        return
                    }
                    applyText(q, texts, at, 0)
                    return
                }
                stats[1]++
                stepRef?.invoke(at + 1)
            }

            // 图片题（听力作业把题面做成图）拿到的是「一组字母」：按空位顺序一个个填，
            // 整题只计一次成功/失败，避免把 7 个空算成 7 道题。
            fun applyLetters(q: QuizQuestion, values: List<String>, slot: Int, at: Int) {
                val limit = minOf(values.size, maxOf(q.inputs, 1))
                if (slot >= limit) {
                    stats[0]++
                    stepRef?.invoke(at + 1)
                    return
                }
                webView.evaluateJavascript(QuizScripts.fillJs(q.index, slot, values[slot])) { result ->
                    val ok = unquote(result).contains("\"ok\":true")
                    val last = slot + 1 >= limit
                    if (last) {
                        if (ok) stats[0]++ else stats[1]++
                        stepRef?.invoke(at + 1)
                    } else {
                        applyLetters(q, values, slot + 1, at)
                    }
                }
            }

            stepRef = { at ->
                if (at >= list.size) {
                    solving = false
                    val firstError = synchronized(errors) { errors.firstOrNull().orEmpty() }
                    solveNote = "填了 " + stats[0] + "/" + stats[2] + " 题" +
                        (if (stats[1] > 0) "，有 " + stats[1] + " 题没拿到答案" else "") +
                        (if (already > 0) "，其中 " + already + " 题本来就有答案、没动" else "") +
                        (if (overwritten > 0) "，改了 " + overwritten + " 道之前的错选" else "") +
                        (if (uncertain > 0) "，" + uncertain + " 题没把握、留给你自己看" else "") +
                        (if (fromBank > 0) "，其中 " + fromBank + " 题来自题库" else "") +
                        (if (noTranscript > 0) "；" + noTranscript + " 道听力题没拿到转写（语音模型没就绪，或当前服务商不支持转写），只能靠图猜" else "") +
                        (if (stats[0] == 0 && firstError.isNotEmpty()) "。原因：" + AiSupport.friendly(firstError) else "") +
                        " · 没提交，自己看一眼再点提交"
                    if (stats[0] > 0) scanNote = "AI 已填 " + stats[0] + " 题（未提交）"
                } else {
                    val q = list[at]
                    val picQuestion = q.images.isNotEmpty() && q.inputs > 0
                    run {
                        solveNote = "第 " + (at + 1) + "/" + list.size + " 题 · " + kindLabel(q.kind) +
                            (if (picQuestion) " · 题面是图片" else "")
                        // webView.url 只能在主线程读 —— 放到后台线程里会直接崩（已实测踩到）
                        val pageUrl = webView.url.orEmpty()
                        // 先读这一题现在的状态：已答且答案一样就不再动，避免把选好的取消掉
                        webView.evaluateJavascript(QuizExtra.currentJs(q.index)) { state ->
                            val prev = parseChecked(state)
                            val prevFilled = parseFilled(state)
                            Thread {
                        // 开跑前先花 1 秒验一次接口：模型名过期是最常见的失败原因，
                        // 以前是「先转写 2~3 分钟、再被 400 打回」，用户看到的就是「又慢又没填」。
                        if (at == 0 && !apiChecked) {
                            apiChecked = true
                            val probe = preflight(context, apiBase, key, model, wantVision = true)
                            if (probe.error.isNotBlank()) {
                                webView.post {
                                    solving = false
                                    solveNote = "AI 接口不能用：" + probe.error
                                    scanNote = "没开始答题，先把接口修好"
                                }
                                return@Thread
                            }
                            if (probe.model.isNotBlank() && probe.model != model) {
                                val fixed = probe.model
                                model = fixed
                                ChaoxingStore.setAiModel(context, fixed)
                                webView.post { solveNote = "模型名已自动改成 " + fixed }
                            }
                        }
                        // 听力题先转写，再把原文塞进同一次提问里 —— 之前是「先问、答不出来才转写」，
                        // 结果模型随便回一个字母就跳过了转写，听力全错。
                        val hasAudio = q.audio.isNotBlank() || q.kind == "listening"
                        // 本地模型也算「配好了」：以前只认云端地址，结果本地模式下被整段跳过
                        val sttConfigured = ChaoxingStore.sttBase(context).isNotBlank() ||
                            ChaoxingStore.sttMode(context) == ChaoxingStore.STT_LOCAL
                        val sttBase = ChaoxingStore.sttBase(context).ifBlank { apiBase }
                        val sttKey2 = ChaoxingStore.sttKey(context).ifBlank { key }
                        val heard = if (hasAudio && sttConfigured) {
                            transcribeAudio(
                                context = context,
                                raw = q.audio,
                                base = pageUrl,
                                apiBase = sttBase,
                                key = sttKey2,
                                sttModel = ChaoxingStore.sttModel(context),
                                onNote = { msg -> webView.post { solveNote = msg } },
                            )
                        } else {
                            ""
                        }
                        if (hasAudio && heard.isBlank()) webView.post { noTranscript++ }
                        // 题面是图片的（Section A/B 那种）：一次问整块，按空位顺序填字母
                        // 题库优先：命中的话直接用，不问模型（图片题先跳过题库，题面在图里对不上文本）
                        if (!picQuestion) {
                            val hit = runCatching { QuizBank.lookup(context, q) }.getOrNull()
                            if (hit != null) {
                                val letters = hit.answer.replace(Regex("[^A-Ha-h]"), "")
                                val ans = if (letters.isNotEmpty() && q.options.isNotEmpty()) {
                                    QuizAnswer(letters = letters.uppercase(), conf = 1.0)
                                } else {
                                    QuizAnswer(texts = listOf(hit.answer), conf = 1.0)
                                }
                                webView.post {
                                    fromBank++
                                    applyAt(q, ans, at, prev, prevFilled)
                                }
                                return@Thread
                            }
                        }
                        if (picQuestion) {
                            // 题面图片挂在超星 CDN 上，要带会话才下得下来；
                            // 直接把这个地址丢给模型，模型那边是抓不到的（会回 4xx），所以先下成 base64 再发。
                            // 顺手缩图重编码：原图常是 1~3 MB 的 PNG，手机上传它是纯等
                            val inline = q.images.mapNotNull { u ->
                                val bytes = HttpText.bytes(u, cookieFor(u), "https://mooc1.chaoxing.com/")
                                if (bytes.isEmpty()) null else ImageInline.dataUri(bytes)
                            }
                            val reply = AiClient.chatImages(
                                apiBase,
                                key,
                                model,
                                QuizPrompt.vision(courseContext(), q.inputs, heard, q.stem),
                                inline,
                            )
                            val values = AiParse.answers(reply.text)
                            if (values.isNotEmpty()) {
                                // 只回了一部分（要 7 个只给 5 个）：照样填，但记一笔让用户自己核
                                if (values.size < q.inputs) webView.post { uncertain++ }
                                webView.post { applyLetters(q, values, 0, at) }
                                return@Thread
                            }
                            // 拿到空答案就别再往下走文字那一路了：那等于同一道题再问一遍，
                            // 白等一轮（以前这里会掉下去，慢一倍）
                            if (!reply.ok) synchronized(errors) { if (errors.size < 3) errors.add(reply.error) }
                            webView.post {
                                uncertain++
                                stepRef?.invoke(at + 1)
                            }
                            return@Thread
                        }
                        // 一次不够：把握 < 0.8 再让它算一遍，取更确定的那个。
                        // 这里刻意不问第三遍 —— 每次提问都是一整轮网络往返，
                        // 一套 25 空的听力，多问一轮就是几十秒，用户体感就是「太慢」。
                        var reply = AiClient.chatResult(
                            apiBase,
                            key,
                            model,
                            QuizPrompt.user(q, courseContext(), heard),
                        )
                        if (!reply.ok) synchronized(errors) { if (errors.size < 3) errors.add(reply.error) }
                        var ans = AiParse.answer(reply.text)
                        if (ans.conf < 0.8 && ans.letters.isNotEmpty()) {
                            val second = AiClient.chatResult(
                                apiBase,
                                key,
                                model,
                                QuizPrompt.retry(q, courseContext(), heard),
                            )
                            if (!second.ok) synchronized(errors) { if (errors.size < 3) errors.add(second.error) }
                            val ans2 = AiParse.answer(second.text)
                            if (ans2.conf >= ans.conf) ans = ans2
                        }
                        // 两轮都不太确定：照样填（用户要的是先填上，自己再核），只是标记出来
                        if (ans.letters.isNotEmpty() && ans.conf < 0.9) {
                            webView.post { uncertain++ }
                        }
                        val finalAns = ans
                        webView.post { applyAt(q, finalAns, at, prev, prevFilled) }
                            }.start()
                        }
                    }
                }
            }
            stepRef?.invoke(0)
        }
    }

    /**
     * 走到作答页就自动开做，只填不交。
     *
     * 只在「确实配了 Key」且「这个地址还没自动做过」时触发一次，
     * 页面重排 / onPageFinished 多次回调都不会重复刷题。
     */
    fun maybeAutoSolve(url: String) {
        if (url.isBlank()) return
        if (page != PAGE_WEB) return
        if (solving) return
        if (ChaoxingStore.aiKey(context).isBlank()) return
        if (autoSolved.contains(url)) return
        val u = url.lowercase()
        // 只看域名路径不够：超星同一份作业，未作答是 /work/view，开始答题才是 dohomework，
        // 而且考试又是另一种路径。所以先粗筛「作业/考试类页面」，再问 DOM 里面有没有可作答控件。
        if (!u.contains("/work/") && !u.contains("/exam")) return
        autoSolved = autoSolved + url
        webView.evaluateJavascript(QuizScripts.QUESTIONS) { raw ->
            val qs = QuizParse.questions(unquote(raw))
            val answerable = qs.any { it.options.isNotEmpty() || it.inputs > 0 || it.editor }
            if (answerable) {
                solveNote = "检测到作答页 · 自动开始（只填不交）"
                webView.postDelayed({ if (!solving) solvePage() }, 900L)
            } else {
                autoSolved = autoSolved - url
            }
        }
    }

    /** 一门课的全套抓取，纯 HTTP，不碰 WebView：
     *
     * 课程页（带 cpi 的那个地址）→ 读出 enc / workEnc / examEnc / openc
     * → 作业列表 + 考试列表并发拉 → 解析。
     *
     * 之所以能这么干：这几个签名值就写在课程页的 hidden input 里，
     * 同一个会话内一直有效，所以没必要开页面、点 tab、再回头读 iframe 地址。
     */
    fun scanOne(course: ScanCourse, cookie: String) {
        val referer = ChaoxingUrls.middle(course)
        // 每个域名单独取 cookie：超星的 vc3 / xxtenc / jrose 这些是分域名签发的，
        // 把所有域名的 cookie 混成一串发过去，服务端会当成异常请求（【9010】验证码）
        val middle = HttpText.get(referer, cookieFor(referer), ChaoxingUrls.COURSE_LIST)
        val keys = CourseHtml.keys(middle)
        var rows: List<ChaoxingTask> = emptyList()
        var note = course.name + " · 没读到签名参数"
        if (keys != null) {
            val t = System.currentTimeMillis().toString()
            val workUrl = CourseHtml.workUrl(course, keys, t)
            val examUrl = CourseHtml.examUrl(course, keys, t)
            val workHtml = HttpText.get(workUrl, cookieFor(workUrl), referer)
            val examHtml = HttpText.get(examUrl, cookieFor(examUrl), referer)
            val workRows = if (workHtml.isEmpty() || WorkHtml.looksLikeLogin(workHtml)) {
                emptyList()
            } else {
                WorkHtml.parse(workHtml, course.name, false)
            }
            val examRows = if (examHtml.isEmpty() || WorkHtml.looksLikeLogin(examHtml)) {
                emptyList()
            } else {
                WorkHtml.parse(examHtml, course.name, true)
            }
            rows = workRows + examRows
            note = course.name + " · 作业 " + workRows.size + " 考试 " + examRows.size
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "CSScan",
                    "native " + course.name + " middle=" + middle.length +
                        " work=" + workHtml.length + "/" + workRows.size +
                        " exam=" + examHtml.length + "/" + examRows.size,
                )
            }
        } else {
            // 【9010】操作异常，请输入图片中的验证码 —— 请求被风控了，记下来
            if (middle.contains("验证码") || middle.contains("9010")) captcha = true
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "CSScan",
                    "no keys " + course.name + " len=" + middle.length +
                        " body=" + middle.replace(Regex("\\s+"), " ").take(300),
                )
            }
        }
        webView.post {
            scanned = TaskScan.merge(scanned, rows)
            tasks = TaskScan.merge(tasks, rows)
            scanLog = scanLog + note
            pending = pending - 1
            progress = if (pending > 0) "还剩 " + pending + " 门" else "收尾"
            maybeFinish()
        }
    }

    /** 所有课程丢进固定大小的线程池并发抓，20 门课也就几秒 */
    fun nativeScanAll() {
        if (!scanning) return
        if (pending > 0) return
        if (courses.isEmpty()) {
            finishScan("没读到课程列表")
            return
        }
        pending = courses.size
        progress = "并发抓取 " + courses.size + " 门课"
        val manager = CookieManager.getInstance()
        val cookie = CookieSnapshot.mergeHead(
            listOf(
                manager.getCookie(ChaoxingUrls.COURSE_LIST).orEmpty(),
                manager.getCookie("https://mooc1.chaoxing.com").orEmpty(),
                manager.getCookie("https://i.chaoxing.com").orEmpty(),
            ),
        )
        // 错开发车：并发太高会被学习通的风控（【9010】验证码）拦下，
        // 每门课间隔 STAGGER_MS 提交，20 门也就多花几秒
        val pool = Executors.newScheduledThreadPool(PARALLEL_COURSE)
        courses.forEachIndexed { index, course ->
            pool.schedule(
                { if (scanning) scanOne(course, cookie) },
                index * STAGGER_MS,
                TimeUnit.MILLISECONDS,
            )
        }
        pool.shutdown()
    }

    fun startScan() {
        if (scanning) return
        scanning = true
        stage = SCAN_COURSES
        cursor = 0
        courses = emptyList()
        // 不清空 tasks：先把上次的清单留在屏幕上，新结果边扫边合并进来
        scanned = emptyList()
        scanLog = emptyList()
        scanNote = ""
        progress = "读取课程列表"
        courseTries = 0
        pending = 0
        captcha = false
        // 扫描全程在后台跑，不把用户拽进网页页
        page = PAGE_HOME
        webView.loadUrl(ChaoxingUrls.COURSE_LIST)
    }

    fun cancelScan() {
        if (!scanning) return
        scanning = false
        stage = SCAN_IDLE
        progress = ""
        page = PAGE_HOME
        scanNote = "扫描已停止"
    }

    /**
     * 读课程列表：`onPageFinished` 时页面上的课程卡片往往还没渲染出来
     * （超星的课程列表是登录后再异步铺的），所以这里按 700ms 轮询重试，
     * 最多 14 次（约 10 秒）才认输。
     */
    fun readCourses() {
        if (!scanning || stage != SCAN_COURSES) return
        val atTry = courseTries
        webView.evaluateJavascript(ChaoxingScripts.COURSES) { raw ->
            if (!scanning || stage != SCAN_COURSES || courseTries != atTry) return@evaluateJavascript
            val found = TaskScan.parseCourses(unquote(raw))
            if (found.isEmpty() && courseTries < 14) {
                courseTries = courseTries + 1
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("CSScan", "courses empty, retry " + courseTries)
                }
                progress = "等课程列表渲染（" + courseTries + "/14）"
                webView.postDelayed({ readCourses() }, 700L)
                return@evaluateJavascript
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.d("CSScan", "courses found=" + found.size)
            }
            courses = found
            scanLog = scanLog + ("课程 " + found.size + " 门")
            if (found.isEmpty()) {
                finishScan("没读到课程列表 · 确认已经真正登录进去")
            } else {
                // 换个档位，防止 onPageFinished 再触发一次读课程、把整轮扫描跑两遍
                stage = SCAN_COLLECT
                cursor = 0
                nativeScanAll()
            }
        }
    }

    /** 页面加载完时的入口：只有「读课程列表」这一档靠页面事件推进，剩下全是原生并发 */
    fun scanStep() {
        if (stage == SCAN_COURSES) readCourses()
    }

    DisposableEffect(webView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            // 桌面 UA 配桌面版页面：不开 overview 模式的话整页比屏幕宽，右边直接被切掉
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = ChaoxingScripts.DESKTOP_UA
            // 题目页文字小，允许双指缩放（不显示那对 +/- 按钮）
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        restoreSession()

        // 只收页面回报的事件，不向外暴露任何 App 能力
        webView.addJavascriptInterface(
            CsBridge { message ->
                if (message.contains("\"ready\"")) probeText = "页面已注入 · " + nowTime()
            },
            BRIDGE_NAME,
        )

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                view.scrollTo(0, 0)
                view.evaluateJavascript(ChaoxingScripts.BOOTSTRAP, null)
                view.evaluateJavascript(ChaoxingScripts.SCROLL_TOP, null)
                view.postDelayed(
                    { view.evaluateJavascript(ChaoxingScripts.SCROLL_TOP, null) },
                    500L,
                )
                // 登录页的表单块画在可视区上方，等它渲染完再摆正
                view.postDelayed(
                    {
                        view.evaluateJavascript(ChaoxingScripts.LOGIN_FIX) { result ->
                            if (BuildConfig.DEBUG) android.util.Log.d("CSProbe", "loginFix=" + result)
                        }
                    },
                    900L,
                )
                rememberSession()
                if (scanning) {
                    scanStep()
                } else {
                    runProbe()
                    maybeAutoSolve(url.orEmpty())
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    // 没有对应 App 就静默丢弃
                }
                return true
            }
        }

        webView.loadUrl(ChaoxingScripts.LOGIN_URL)
        onDispose { webView.destroy() }
    }

    // 先把上次扫到的清单铺出来，别让人对着空白等同步
    LaunchedEffect(Unit) {
        if (tasks.isNotEmpty()) return@LaunchedEffect
        val cached = ChaoxingStore.tasksJson(context)
        if (cached.isBlank()) return@LaunchedEffect
        val restored = TaskCache.decode(cached)
        if (restored.isNotEmpty()) {
            tasks = restored
            val at = ChaoxingStore.tasksAt(context)
            if (at > 0L) scanNote = "上次同步 " + timeLabel(at)
        }
    }

    // 登录后自动来一遍：课程列表 → 每门课的作业 → 考试
    LaunchedEffect(signedIn) {
        if (signedIn && !autoScanTried) {
            autoScanTried = true
            startScan()
        }
    }

    BackHandler(enabled = page == PAGE_WEB) {
        if (scanning) {
            cancelScan()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            page = PAGE_HOME
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // WebView 一直挂在树上：扫描在后台跑，屏幕上看不到「过程」，只看结果。
        // 隐藏时缩到 1dp —— 页面照常加载、DOM 照常可点，只是不占地方。
        WebSurface(
            webView = webView,
            modifier = if (page == PAGE_WEB) {
                // 顶部让出工具栏和地址行的高度，否则页面的标题栏和提交按钮会被压住
                Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, end = 14.dp, top = 108.dp, bottom = 46.dp)
            } else {
                Modifier.size(1.dp)
            },
        )

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            if (page == PAGE_HOME) {
                HomePane(
                    palette = palette,
                    signedIn = signedIn,
                    savedAt = savedAt,
                    open = TaskScan.open(tasks),
                    done = TaskScan.done(tasks),
                    scanNote = scanNote,
                    scanning = scanning,
                    progress = progress,
                    onScanAll = { startScan() },
                    onOpenTask = { openTask(it) },
                    submitMode = submitMode,
                    hasKey = hasKey,
                    setupOpen = setupOpen,
                    keyDraft = keyDraft,
                    modelDraft = modelDraft,
                    onLogin = {
                        urlText = ChaoxingScripts.LOGIN_URL
                        page = PAGE_WEB
                        webView.loadUrl(ChaoxingScripts.LOGIN_URL)
                    },
                    onOpenWeb = { page = PAGE_WEB },
                    onToggleSetup = { setupOpen = !setupOpen },
                    onKeyDraft = { keyDraft = it },
                    onModelDraft = { modelDraft = it },
                    onSaveKey = {
                        if (keyDraft.isNotBlank()) {
                            ChaoxingStore.setAiKey(context, keyDraft)
                            hasKey = true
                            keyDraft = ""
                        }
                        ChaoxingStore.setAiModel(context, modelDraft)
                    },
                    onClearKey = {
                        ChaoxingStore.setAiKey(context, "")
                        hasKey = false
                        keyDraft = ""
                    },
                    onModeChange = { mode ->
                        submitMode = mode
                        ChaoxingStore.setSubmitMode(context, mode)
                    },
                    onLogout = { logout() },
                )
            } else {
                WebPane(
                    palette = palette,
                    probeText = probeText,
                    frameSrc = frameSrc,
                    urlText = urlText,
                    scanning = scanning,
                    progress = if (openTitle.isEmpty()) progress else openTitle,
                    scanLog = scanLog,
                    solving = solving,
                    solveNote = solveNote,
                    onUrlChange = { urlText = it },
                    onBack = { if (scanning) cancelScan() else page = PAGE_HOME },
                    onRefresh = { webView.reload() },
                    onProbe = { runProbe() },
                    onScan = { scanPageOnly() },
                    onSolve = { solvePage() },
                    onPrev = { webView.evaluateJavascript("window.scrollBy(0,-620)", null) },
                    onNext = { webView.evaluateJavascript("window.scrollBy(0,620)", null) },
                    onList = { page = PAGE_HOME },
                    onOpenFrame = { webView.loadUrl(it) },
                    onOpenUrl = { if (urlText.isNotBlank()) webView.loadUrl(urlText.trim()) },
                )
            }
        }

    }
}

/** 小组件里唯一的 WebView 宿主：只负责摆放，不做任何业务。 */
@Composable
private fun WebSurface(webView: WebView, modifier: Modifier) {
    AndroidView(
        factory = { webView },
        modifier = modifier.clip(RoundedCornerShape(18.dp)),
    )
}

// ---------------------------------------------------------------- 首页看板

@Composable
private fun HomePane(
    palette: GlassPalette,
    signedIn: Boolean,
    savedAt: Long,
    open: List<ChaoxingTask>,
    done: List<ChaoxingTask>,
    scanNote: String,
    scanning: Boolean,
    progress: String,
    onScanAll: () -> Unit,
    onOpenTask: (ChaoxingTask) -> Unit,
    submitMode: String,
    hasKey: Boolean,
    setupOpen: Boolean,
    keyDraft: String,
    modelDraft: String,
    onLogin: () -> Unit,
    onOpenWeb: () -> Unit,
    onToggleSetup: () -> Unit,
    onKeyDraft: (String) -> Unit,
    onModelDraft: (String) -> Unit,
    onSaveKey: () -> Unit,
    onClearKey: () -> Unit,
    onModeChange: (String) -> Unit,
    onLogout: () -> Unit,
) {
    // 0 = 未完成，1 = 已完成。只做展示，扫描在后台跑。
    var tab by rememberSaveable { mutableStateOf(0) }
    val pending = remember(open) {
        open.sortedWith(
            compareByDescending<ChaoxingTask> { it.exam }
                .thenBy { it.course }
                .thenBy { it.title }
        )
    }
    val finished = remember(done) {
        done.sortedWith(compareBy<ChaoxingTask> { it.course }.thenBy { it.title })
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(6.dp))

            // 状态行：只说「同步到哪了」，不展示扫描过程
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (signedIn) palette.success else palette.warn)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        scanning -> progress.ifEmpty { "正在同步课程…" }
                        !signedIn -> "还没登录"
                        scanNote.isNotEmpty() -> scanNote
                        savedAt > 0L -> "上次同步 " + timeLabel(savedAt)
                        else -> "已登录"
                    },
                    color = palette.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                GlassButton(
                    text = if (setupOpen) "收起" else "设置",
                    onClick = onToggleSetup,
                    modifier = Modifier.width(56.dp),
                    height = 30.dp,
                    kind = GlassButtonKind.Ghost,
                )
            }
            Spacer(Modifier.height(10.dp))

            // 两个分区：没做完的 / 做完了的
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    text = "未完成 " + pending.size,
                    onClick = { tab = 0 },
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                    kind = if (tab == 0) GlassButtonKind.Primary else GlassButtonKind.Ghost,
                )
                GlassButton(
                    text = "已完成 " + finished.size,
                    onClick = { tab = 1 },
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                    kind = if (tab == 1) GlassButtonKind.Primary else GlassButtonKind.Ghost,
                )
            }
            Spacer(Modifier.height(10.dp))

            TaskGroup(
                title = if (tab == 0) "未完成" else "已完成",
                count = if (tab == 0) pending.size else finished.size,
                items = if (tab == 0) pending else finished,
                empty = if (tab == 0) "没有未完成的作业或考试" else "还没有已完成的记录",
                palette = palette,
                onOpen = onOpenTask,
                action = if (tab == 0) {
                    {
                        GlassButton(
                            text = if (scanning) "同步中" else "重新同步",
                            onClick = onScanAll,
                            modifier = Modifier.width(84.dp),
                            height = 30.dp,
                            enabled = !scanning,
                        )
                    }
                } else {
                    null
                },
            )

            if (setupOpen) {
                Spacer(Modifier.height(10.dp))
                LiquidGlass(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    blurRadius = 16.dp,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Column {
                        GlassLabel("只做题 · 从不提交")
                        Spacer(Modifier.height(4.dp))
                        GlassLabel("AI 只回填答案，提交按钮永远留给你自己点。")
                        Spacer(Modifier.height(10.dp))
                        // Key 统一放到「设置」页，这里只显示状态
                        GlassLabel(
                            if (hasKey) {
                                "AI Key：已配置（要改去「设置 → AI Key」）"
                            } else {
                                "AI Key：还没填 · 去「设置 → AI Key」填一条就能用 AI 答题"
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        GlassLabel(
                            if (savedAt > 0L && signedIn) {
                                "会话存于 " + timeLabel(savedAt) + "，换设备要重新登录"
                            } else {
                                "登录走网页，App 不存账密"
                            }
                        )
                        if (signedIn) {
                            Spacer(Modifier.height(10.dp))
                            GlassButton(
                                text = "退出学习通登录",
                                onClick = onLogout,
                                modifier = Modifier.fillMaxWidth(),
                                height = 38.dp,
                                kind = GlassButtonKind.Ghost,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
        }

        if (signedIn) {
            GlassButton(
                text = "打开学习通网页",
                onClick = onOpenWeb,
                modifier = Modifier.fillMaxWidth(),
                height = 38.dp,
                kind = GlassButtonKind.Ghost,
            )
        } else {
            GlassButton(
                text = "登录学习通",
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                height = 46.dp,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** 一组任务：待完成 / 已完成各一张卡 */
@Composable
private fun TaskGroup(
    title: String,
    count: Int,
    items: List<ChaoxingTask>,
    empty: String,
    palette: GlassPalette,
    onOpen: (ChaoxingTask) -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    LiquidGlass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        blurRadius = 16.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = count.toString(),
                    color = palette.textTertiary,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                if (action != null) action()
            }
            Spacer(Modifier.height(8.dp))
            if (items.isEmpty()) {
                Text(
                    text = empty,
                    color = palette.textTertiary,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
            } else {
                items.forEachIndexed { index, task ->
                    TaskRow(task, palette, onOpen)
                    if (index != items.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, color: Color, palette: GlassPalette) {
    Column {
        Text(
            text = value,
            color = color,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = palette.textTertiary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun TaskRow(task: ChaoxingTask, palette: GlassPalette, onOpen: (ChaoxingTask) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.fieldFill)
            .clickable { onOpen(task) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(courseColor(task.course))
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = buildString {
                    append(task.course)
                    if (task.exam) append(" · 考试")
                    if (task.due.isNotEmpty()) append(" · 截止 ").append(task.due)
                },
                color = palette.textTertiary,
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = task.title,
                color = palette.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusChip(task.status, palette)
    }
}

@Composable
private fun StatusChip(status: TaskStatus, palette: GlassPalette) {
    val tint = when (status) {
        TaskStatus.Todo -> palette.accent
        TaskStatus.Done -> palette.success
        TaskStatus.Expired -> palette.danger
        TaskStatus.Unknown -> palette.warn
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = status.label,
            color = tint,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 只按课程名取个稳定颜色，不做任何业务含义 */
private fun courseColor(name: String): Color {
    val palette = listOf(
        Color(0xFF4F6BFF),
        Color(0xFF14A8A0),
        Color(0xFFF0913A),
        Color(0xFF7B61FF),
        Color(0xFFE0576B),
    )
    if (name.isEmpty()) return palette[0]
    var hash = 0
    for (c in name) hash = (hash * 31 + c.code) and 0x7fffffff
    return palette[hash % palette.size]
}

// ---------------------------------------------------------------- 网页页

@Composable
private fun WebPane(
    palette: GlassPalette,
    probeText: String,
    frameSrc: String,
    urlText: String,
    scanning: Boolean,
    progress: String,
    scanLog: List<String>,
    solving: Boolean,
    solveNote: String,
    onUrlChange: (String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onProbe: () -> Unit,
    onScan: () -> Unit,
    onSolve: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onList: () -> Unit,
    onOpenFrame: (String) -> Unit,
    onOpenUrl: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(6.dp))

        if (scanning) {
            LiquidGlass(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                blurRadius = 12.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(palette.accent)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "自动扫描中",
                            color = palette.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = progress.ifEmpty { "准备中" },
                            color = palette.textTertiary,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (scanLog.isNotEmpty()) {
                            Text(
                                text = scanLog.last(),
                                color = palette.textTertiary,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    GlassButton(
                        text = "停",
                        onClick = onBack,
                        modifier = Modifier.width(44.dp),
                        height = 28.dp,
                        kind = GlassButtonKind.Ghost,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassButton(
                text = "返回",
                onClick = onBack,
                modifier = Modifier.width(58.dp),
                height = 32.dp,
                kind = GlassButtonKind.Ghost,
            )
            Spacer(Modifier.weight(1f))
            GlassButton(
                text = if (solving) "答题中…" else "开始答题",
                onClick = onSolve,
                modifier = Modifier.width(92.dp),
                height = 32.dp,
                enabled = !solving,
            )
            Spacer(Modifier.width(6.dp))
            GlassButton(
                text = "刷新",
                onClick = onRefresh,
                modifier = Modifier.width(58.dp),
                height = 32.dp,
                kind = GlassButtonKind.Ghost,
            )
            Spacer(Modifier.width(6.dp))
            GlassButton(
                text = "结构",
                onClick = onProbe,
                modifier = Modifier.width(58.dp),
                height = 32.dp,
                kind = GlassButtonKind.Ghost,
            )
        }
        if (solveNote.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = solveNote,
                color = palette.textSecondary,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(
                value = urlText,
                onValueChange = onUrlChange,
                modifier = Modifier.weight(1f),
                placeholder = "粘一个学习通页面地址",
                height = 36.dp,
                textSize = 11,
            )
            Spacer(Modifier.width(6.dp))
            GlassButton(
                text = "打开",
                onClick = onOpenUrl,
                modifier = Modifier.width(60.dp),
                height = 36.dp,
                kind = GlassButtonKind.Ghost,
            )
        }
        Spacer(Modifier.height(6.dp))
        // WebView 在上一层，这里留出透明区让页面透出来
        Spacer(Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = probeText.ifEmpty { "页面加载完会自动报告结构" },
                color = palette.textTertiary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (frameSrc.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                GlassButton(
                    text = "进工作区",
                    onClick = { onOpenFrame(frameSrc) },
                    height = 28.dp,
                    kind = GlassButtonKind.Ghost,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 底部固定操作条：翻题不用满屏找按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassButton(
                text = "上一题",
                onClick = onPrev,
                modifier = Modifier.weight(1f),
                height = 34.dp,
                kind = GlassButtonKind.Ghost,
            )
            Spacer(Modifier.width(6.dp))
            GlassButton(
                text = "下一题",
                onClick = onNext,
                modifier = Modifier.weight(1f),
                height = 34.dp,
                kind = GlassButtonKind.Ghost,
            )
            Spacer(Modifier.width(6.dp))
            GlassButton(
                text = "回清单",
                onClick = onList,
                modifier = Modifier.weight(1f),
                height = 34.dp,
                kind = GlassButtonKind.Ghost,
            )
        }
    }
}

// ---------------------------------------------------------------- 桥 / 小工具

private const val BRIDGE_NAME = "__csBridge"

/**
 * 只读的回声桥：页面里的脚本能往 Kotlin 侧报事件，Kotlin 侧不对外暴露任何能力。
 * 加载的是第三方页面，注入对象越窄越好。
 */
private class CsBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun emit(json: String?) {
        val message = json.orEmpty()
        if (message.isEmpty()) return
        Handler(Looper.getMainLooper()).post { onMessage(message) }
    }
}

private data class ProbeInfo(val text: String, val frame: String)

private fun parseProbe(json: String): ProbeInfo {
    if (json.isBlank()) return ProbeInfo("", "")
    return try {
        val o = JSONObject(json)
        val kind = o.optString("t")
        val text = when (kind) {
            "probe" -> buildString {
                append(o.optString("host"))
                val path = o.optString("path")
                if (path.isNotEmpty() && path != "/") append(path.take(20))
                if (o.optBoolean("onLoginPage")) append(" · 登录页")
                val q = o.optInt("questions")
                val c = o.optInt("choices")
                val i = o.optInt("inputs")
                if (q > 0) append(" · 题 ").append(q)
                if (c > 0) append(" · 选项 ").append(c)
                if (i > 0) append(" · 输入 ").append(i)
                val frames = o.optInt("hasFrames")
                if (frames > 0) append(" · iframe ").append(frames)
            }
            "no-bootstrap" -> "这一页还没挂上注入脚本"
            "error" -> "注入报错：" + o.optString("message")
            else -> json.take(80)
        }
        // 跨域 iframe 里的题 evaluateJavascript 碰不到，只把地址带回去让用户提到顶层
        val frames = o.optJSONArray("frames")
        var src = ""
        if (frames != null) {
            for (index in 0 until frames.length()) {
                val f = frames.optJSONObject(index) ?: continue
                val candidate = f.optString("src")
                if (candidate.isNotEmpty() && candidate.startsWith("http")) {
                    src = candidate
                    break
                }
            }
        }
        ProbeInfo(text, src)
    } catch (e: Exception) {
        ProbeInfo(json.take(80), "")
    }
}

private val CS_HOSTS = listOf(
    "i.chaoxing.com",
    "passport2.chaoxing.com",
    "mooc1.chaoxing.com",
    "mooc2-ans.chaoxing.com",
    "www.chaoxing.com",
)

private fun readCookies(): String {
    val manager = CookieManager.getInstance()
    val entries = ArrayList<Pair<String, String>>(CS_HOSTS.size)
    for (host in CS_HOSTS) {
        val cookie = manager.getCookie("https://$host") ?: continue
        if (cookie.isNotBlank()) entries.add(host to cookie)
    }
    return CookieSnapshot.encode(entries)
}

/**
 * 只取这个 URL 自己在 CookieManager 里的那份 cookie。
 *
 * 超星按域名签发 vc3 / xxtenc / jrose 之类的令牌，把几个域名的 cookie
 * 拼成一串发出去，服务端会判定为异常请求（返回【9010】请输入验证码）。
 */
private fun cookieFor(url: String): String =
    runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()

/**
 * 听力的音频地址有两种形态：
 *  - `object:` 前缀 + 若干 objectid（超星 audioplay.html 的 iframe），要用 /ananas/status/<id> 换真实下载地址；
 *  - 普通 URL（老页面直接放 audio）。
 * 拿到字节后逐段转写，拼成一段原文交给模型。
 *
 * 转写有两条路（见 [ChaoxingStore.sttMode]）：
 *  - 本地：sherpa-onnx + SenseVoice，音频不出手机，但模型要首次下载；
 *  - 云端：POST 到用户自己填的 STT 接口。
 * 本地是默认值；本地模型没就绪、又配了云端，就先退回云端顶上，并在提示里说清楚。
 */
private fun transcribeAudio(
    context: android.content.Context,
    raw: String,
    base: String,
    apiBase: String,
    key: String,
    sttModel: String = "",
    onNote: (String) -> Unit = {},
): String {
    // 同一个作业里几个填空块共用同一批音频，不缓存的话每块都会重下一遍（8 段 mp3 × 3 块）
    synchronized(audioCache) { audioCache[raw] }?.let { return it }
    val urls = if (raw.startsWith("object:")) {
        raw.removePrefix("object:")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { resolveAudioUrl(it, base) }
    } else {
        listOf(absoluteUrl(raw, base)).filter { it.isNotBlank() }
    }

    var local = ChaoxingStore.sttMode(context) == ChaoxingStore.STT_LOCAL
    // 选了云端但没填转写地址：别把音频 POST 到聊天接口上去（会白等一个 400）
    if (!local && ChaoxingStore.sttBase(context).isBlank()) {
        onNote("云端转写没填接口地址，这次不转写")
        return ""
    }
    if (local && !LocalAsr.isReady(context)) {
        if (apiBase.isNotBlank() && key.isNotBlank()) {
            // 配了云端就别让用户干等 228 MB 下载
            local = false
            onNote("语音模型还没下载，这次先用云端转写")
        } else {
            // 没云端可退，只能现在下（几分钟，进度打在答题提示上）
            onNote("首次使用，正在下载语音模型（约 " + LocalAsr.sizeMb() + " MB）…")
            if (!LocalAsr.prepare(context) { msg -> onNote(msg) }) {
                onNote("语音模型没准备好：" + LocalAsr.error())
                return ""
            }
            local = true
        }
    }

    val sb = StringBuilder()
    // 8 段音频加起来二三十 MB，串行下载在慢网下能拖掉好几分钟 —— 并发拉，最多 4 路。
    // 这里只是把字节先拿到手，转写仍然是逐段的（模型不能并行跑）。
    val blobs = arrayOfNulls<ByteArray>(urls.size)
    if (urls.size > 1) {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, urls.size))
        urls.forEachIndexed { i, u -> pool.execute { blobs[i] = HttpText.bytes(u, cookieFor(u), base) } }
        pool.shutdown()
        pool.awaitTermination(15, java.util.concurrent.TimeUnit.MINUTES)
    } else {
        urls.forEachIndexed { i, u -> blobs[i] = HttpText.bytes(u, cookieFor(u), base) }
    }

    blobs.forEach { b ->
        val bytes = b ?: return@forEach
        if (bytes.isEmpty()) return@forEach
        val model = sttModel.ifBlank { "whisper-1" }
        // 有的接口只收 30 秒以内的音频（智谱 GLM-ASR），所以先切成小段再逐段转写；
        // 切不出来（解码失败）就退回整文件上传。
        // 本地模型没有 30 秒限制，切大一点少几次解码/建流的开销；云端接口那边还是 26 秒
        val chunks = AudioChunks.chunkAudio(bytes, if (local) LOCAL_CHUNK_SECONDS else 26)
        if (chunks.isEmpty()) {
            if (local) return@forEach // 本地要 PCM，解码失败就真的没辙了
            val text = AiClient.transcribe(apiBase, key, bytes, model)
            if (text.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }
        } else if (local) {
            // 逐段转、逐段丢：一整份 WAV 副本 30 MB 上下，攥着等 ASR 跑完纯属浪费内存。
            // removeAt(0) 之后那段字节立刻可回收，转写阶段的峰值就只剩「一段采样 + 模型工作区」。
            val queue = ArrayList(chunks)
            val total = queue.size
            var done = 0
            while (queue.isNotEmpty()) {
                val c = queue.removeAt(0)
                done++
                onNote("本地转写 " + done + "/" + total + " 段…")
                val samples = AudioChunks.toMono16k(AudioChunks.pcmOf(c.wav), c.sampleRate, c.channels)
                val text = LocalAsr.transcribe(context, samples)
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            }
        } else {
            chunks.forEach { c ->
                val text = AiClient.transcribe(apiBase, key, c.wav, model, "a.wav")
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            }
        }
    }
    val result = sb.toString()
    if (result.isNotBlank()) synchronized(audioCache) { audioCache[raw] = result }
    return result
}

/** 开跑前的接口自检结果：model 非空表示「模型名该换成这个」，error 非空表示「这轮没戏」 */
private class Preflight(val model: String, val error: String)

/**
 * 花 1 秒验一次接口，别等转写完两分钟才被 400 打回来。
 *
 * 判据用 `GET /models`（各家都有，最便宜）：
 *  - 列表里有当前模型 → 直接过
 *  - 列表里没有 → 说明这个名字过期/写错（DeepSeek 的 deepseek-chat 就是这样），自动换成清单里最合适的
 *  - 401/403 → Key 本身不行，整轮停下并说清楚
 *  - 404/405（中转站不实现 /models）→ 不能判死刑，放行
 */
private fun preflight(
    context: android.content.Context,
    base: String,
    key: String,
    current: String,
    wantVision: Boolean,
): Preflight {
    if (key.isBlank()) return Preflight("", "还没填 Key")
    val (ids, err) = AiClient.listModels(base, key)
    if (ids.isEmpty()) {
        val hard = err.contains("401") || err.contains("403") ||
            err.contains("invalid", true) || err.contains("unauthorized", true) ||
            err.contains("验证不正确", true) || err.contains("无效", true)
        return if (hard) Preflight("", "Key 无效（" + err.take(90) + "）") else Preflight("", "")
    }
    if (ids.any { it.equals(current, true) }) return Preflight("", "")
    val picked = (if (wantVision) AiProbe.pickVision(ids) else null) ?: AiProbe.pickAny(ids)
    return if (picked == null) Preflight("", "服务商没有能用的聊天模型") else Preflight(picked, "")
}

/** 本地 ASR 的切段长度：模型是按 30 秒窗口训练的，正好卡满，别超 */
private const val LOCAL_CHUNK_SECONDS = 30

/**
 * 转写结果缓存：key 是页面里那串原始 audio 描述（objectid 列表）。
 *
 * 全局 Map 会跟着 App 一直活着，所以封顶 8 条（LinkedHashMap 按访问顺序淘汰最旧的那条）。
 * 一个作业最多 8 段，够用；再多的留着只是白占内存。
 */
private val audioCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 8
}

/** objectid → 真实音频地址（/ananas/status 返回的 download / http 字段） */
private fun resolveAudioUrl(objectId: String, referer: String): String? {
    val metaUrl = "https://mooc1.chaoxing.com/ananas/status/" + objectId
    val meta = HttpText.get(metaUrl, cookieFor(metaUrl), referer)
    if (meta.isBlank()) return null
    return try {
        val obj = JSONObject(meta)
        val download = obj.optString("download", "")
        val http = obj.optString("http", "")
        when {
            download.startsWith("http") -> download
            http.startsWith("http") -> http
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/** 页面里的音频地址经常是相对路径（/star3/… 或 //cdn/…），这里补成绝对地址 */
private fun absoluteUrl(raw: String, base: String?): String {    val u = raw.trim()
    if (u.isEmpty()) return ""
    if (u.startsWith("//")) return "https:" + u
    if (u.startsWith("http://", true) || u.startsWith("https://", true)) return u
    val b = base?.trim().orEmpty()
    if (b.isEmpty()) return u
    return try {
        java.net.URI(b).resolve(u).toString()
    } catch (e: Exception) {
        u
    }
}

/** evaluateJavascript 回的是 JSON 字符串字面量，包一层数组再取第 0 个就等于反转义 */
private fun unquote(raw: String?): String {
    val s = raw?.trim().orEmpty()
    if (s.length < 2 || s == "null") return ""
    return try {
        org.json.JSONArray("[$s]").optString(0)
    } catch (e: Exception) {
        s
    }
}

/** 从脚本回的小 JSON 里取一个字段；取不到给空串，不抛异常（脚本可能压根没跑起来） */private fun jsonField(raw: String, name: String): String {
    if (raw.isBlank()) return ""
    return try {
        val obj = JSONObject(raw)
        if (!obj.has(name)) "" else obj.optString(name).trim()
    } catch (e: Exception) {
        ""
    }
}

private fun nowTime(): String {
    val cal = java.util.Calendar.getInstance()
    return String.format(
        java.util.Locale.US,
        "%02d:%02d",
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

private fun timeLabel(at: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = at
    return String.format(
        java.util.Locale.US,
        "%02d-%02d %02d:%02d",
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

/** 读 QuizExtra.currentJs 的结果：已选中的字母 */
private fun parseChecked(raw: String): List<String> = runCatching {
    val json = org.json.JSONObject(raw.trim().removeSurrounding("\"").replace("\\\"", "\""))
    val arr = json.optJSONArray("checked") ?: return emptyList()
    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
}.getOrDefault(emptyList())

/** 读 QuizExtra.currentJs 的结果：这道题已经填了几个空 */
private fun parseFilled(raw: String): Int = runCatching {
    org.json.JSONObject(raw.trim().removeSurrounding("\"").replace("\\\"", "\""))
        .optInt("filled", 0)
}.getOrDefault(0)
