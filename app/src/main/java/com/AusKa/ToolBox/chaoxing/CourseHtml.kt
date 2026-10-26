package com.AusKa.ToolBox.chaoxing

/**
 * 课程主页里藏着的这一轮会话的签名参数。
 *
 * 作业 / 考试列表地址里的 `enc` 是按模块单独算的（作业一份、考试一份），
 * 自己拼不出来 —— 少一个或者拿错一份就是「无权限的操作！」。
 * 好在课程主页的 HTML 里已经把这几个值放在 hidden input 里了：
 *
 * ```
 * <input type="hidden" id="enc"     value="..."/>  // 当前的 stuenc
 * <input type="hidden" id="workEnc" value="..."/>  // 作业列表那份 enc
 * <input type="hidden" id="examEnc" value="..."/>  // 考试列表那份 enc
 * <input type="hidden" id="openc"   value="..."/>  // 考试页还要的 openc
 * ```
 *
 * 所以只要把课程主页拉下来、把四个值读出来，作业和考试的列表页就能**纯 HTTP**
 * 并发去取，不用再开 WebView 一门一门点 tab。
 */
internal data class CourseKeys(
    val enc: String,
    val workEnc: String,
    val examEnc: String,
    val openc: String,
)

internal object CourseHtml {

    private val ENC = Regex("id=\"enc\"[^>]*value=\"([^\"]*)\"")
    private val WORK_ENC = Regex("id=\"workEnc\"[^>]*value=\"([^\"]*)\"")
    private val EXAM_ENC = Regex("id=\"examEnc\"[^>]*value=\"([^\"]*)\"")
    private val OPENC = Regex("id=\"openc\"[^>]*value=\"([^\"]*)\"")

    /** 从课程主页 HTML 里读出四个签名参数，缺任何一个都返回 null（这门课只能跳过） */
    fun keys(html: String): CourseKeys? {
        if (html.isEmpty()) return null
        val enc = ENC.find(html)?.groupValues?.getOrNull(1).orEmpty()
        val work = WORK_ENC.find(html)?.groupValues?.getOrNull(1).orEmpty()
        val exam = EXAM_ENC.find(html)?.groupValues?.getOrNull(1).orEmpty()
        val openc = OPENC.find(html)?.groupValues?.getOrNull(1).orEmpty()
        if (enc.isEmpty() || work.isEmpty()) return null
        return CourseKeys(enc = enc, workEnc = work, examEnc = exam, openc = openc)
    }

    /** 作业列表：cookie 里带会话，地址里的签名全都来自 [CourseKeys] */
    fun workUrl(course: ScanCourse, keys: CourseKeys, t: String): String =
        "https://mooc1.chaoxing.com/mooc2/work/list" +
            "?courseId=" + course.courseId +
            "&classId=" + course.classId +
            (if (course.cpi.isEmpty()) "" else "&cpi=" + course.cpi) +
            "&ut=s&t=" + t +
            "&stuenc=" + keys.enc +
            "&enc=" + keys.workEnc

    /** 考试列表：参数名是小写的 courseid/clazzid，还要额外带 openc 和考试那份 enc */
    fun examUrl(course: ScanCourse, keys: CourseKeys, t: String): String =
        "https://mooc1.chaoxing.com/exam-ans/mooc2/exam/exam-list" +
            "?courseid=" + course.courseId +
            "&clazzid=" + course.classId +
            (if (course.cpi.isEmpty()) "" else "&cpi=" + course.cpi) +
            "&ut=s&t=" + t +
            "&stuenc=" + keys.enc +
            "&enc=" + keys.examEnc +
            (if (keys.openc.isEmpty()) "" else "&openc=" + keys.openc)

    /** 会话过期时超星会把列表页换成登录页，这种 HTML 直接丢掉，别拿去解析 */
    fun looksLikeLogin(html: String): Boolean {
        if (html.isEmpty()) return false
        if (html.length < 4096 && (html.contains("用户登录") || html.contains("passport2"))) return true
        return html.contains("name=\"uname\"") && html.contains("name=\"password\"")
    }
}
