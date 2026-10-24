package com.tencent.ToolBox.net

import com.tencent.ToolBox.data.Course
import com.tencent.ToolBox.data.Timetable
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import java.math.BigInteger

/** 正方返回的错误，直接拿 message 显示给用户 */
class JwglxtException(message: String) : Exception(message)

/**
 * 正方教务系统客户端。
 *
 * 不同学校部署的**路径前缀不一样**：常见是 `/jwglxt/xtgl/login_slogin.html`，
 * 也有把 jwglxt 干掉、直接 `/xtgl/login_slogin.html` 的（部分学校是这种）。
 * 所以前缀不能写死，第一次用的时候探一下，登录页能返回带 csrftoken 的 HTML 就算命中。
 *
 * 登录流程：
 *  1. `GET  {prefix}/xtgl/login_slogin.html`       —— 拿 csrftoken，顺便看要不要验证码
 *  2. `GET  {prefix}/xtgl/login_getPublicKey.html` —— 拿 RSA 公钥（modulus/exponent，base64）
 *  3. `POST {prefix}/xtgl/login_slogin.html`       —— 密码 RSA 加密后 **再 base64** 提交
 *
 * 注意密码这一步：前端是 `hex2b64(rsaKey.encrypt(pwd))`，也就是密文字节再做 base64，
 * 不是直接提交十六进制串 —— 这个搞错了服务端一律回「用户名或密码不正确」。
 *
 * 课表：`POST {base}/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151`，前缀与登录可能不同（有的学校
 * 课表接口就在根路径下），所以两个都试一次，认返回里有 `kbList` 的那个。
 *
 * 没用 OkHttp —— 这里只有五个请求、cookie 也就两三个，手写 [CookieJar] 比拖进来 800KB 的依赖划算。
 */
class JwglxtSession(rawBase: String) {

    val base: String = normalizeBase(rawBase)
    private val jar = CookieJar()
    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** 登录接口前缀，`""` 或 `"/jwglxt"`，第一次 [openLoginPage] 时探出来 */
    private var prefix: String? = null

    /** 课表接口前缀，与登录前缀不一定相同 */
    private var kbcxPrefix: String? = null

    data class LoginPage(
        val csrfToken: String,
        val needCaptcha: Boolean,
        val captchaUrl: String,
    )

    /** 打开登录页：探前缀、拿 csrftoken，并判断这套系统要不要验证码 */
    fun openLoginPage(): LoginPage {
        var html = ""
        var hit = ""
        for (candidate in PREFIX_CANDIDATES) {
            val body = runCatching { get("$base$candidate/xtgl/login_slogin.html") }.getOrNull() ?: continue
            if (body.contains("csrftoken")) {
                html = body
                hit = candidate
                break
            }
        }
        if (hit.isEmpty() && html.isEmpty()) {
            throw JwglxtException("打不开登录页，确认地址能进教务系统首页（要带 http:// 或 https://）")
        }
        prefix = hit

        val token = Regex("""name="csrftoken"[^>]*value="([^"]*)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""id="csrftoken"[^>]*value="([^"]*)"""")
                .find(html)?.groupValues?.get(1)
            ?: ""

        // 判断要不要验证码只看登录页有没有那个输入框。
        // 有的学校 /kaptcha 接口照样能出图，但页面根本没有填验证码的地方
        // —— 这时候硬塞一个 yzm 上去，服务端会当成错误请求直接拒掉。
        val needCaptcha = Regex("""name=["']yzm["']""").containsMatchIn(html) ||
            Regex("""id=["']yzm["']""").containsMatchIn(html) ||
            html.contains("kaptcha", ignoreCase = true)

        val captchaUrl = "$base$hit/kaptcha?time=${System.currentTimeMillis()}"

        return LoginPage(csrfToken = token, needCaptcha = needCaptcha, captchaUrl = captchaUrl)
    }

    /** 拉一次验证码图片的字节 */
    fun loadCaptcha(): ByteArray = getBytes(captchaUrl())

    private fun captchaUrl(): String = "$base${prefix.orEmpty()}/kaptcha?time=${System.currentTimeMillis()}"

    /**
     * 登录。失败抛 [JwglxtException]，message 是可以直接给用户看的中文。
     */
    fun login(user: String, pass: String, captcha: String?, page: LoginPage) {
        val p = prefix ?: ""
        val keyJson = get("$base$p/xtgl/login_getPublicKey.html?time=${System.currentTimeMillis()}")
        val modulus = runCatching { JSONObject(keyJson).optString("modulus") }.getOrDefault("")
        val exponent = runCatching { JSONObject(keyJson).optString("exponent") }.getOrDefault("")
        if (modulus.isBlank() || exponent.isBlank()) {
            throw JwglxtException("拿不到登录公钥，教务地址可能不对")
        }

        val encrypted = rsaEncrypt(pass, modulus, exponent)

        val form = linkedMapOf(
            "csrftoken" to page.csrfToken,
            "language" to "zh_CN",
            "yhm" to user,
            "mm" to encrypted,
            "mm2" to encrypted,
        )
        if (!captcha.isNullOrBlank()) form["yzm"] = captcha

        val (body, finalUrl) = post(
            url = "$base$p/xtgl/login_slogin.html",
            form = form,
            referer = "$base$p/xtgl/login_slogin.html",
        )

        // Android 的 HttpURLConnection 跟完重定向后 getURL() 不一定更新成最终地址，
        // 所以不能只看 URL —— 登录成功页本身就带着 initMenu 的痕迹，用它判断更稳。
        val ok = finalUrl.contains("index_initMenu") ||
            finalUrl.contains("xtgl/index") ||
            body.contains("index_initMenu") ||
            body.contains("initMenu") ||
            (!body.contains("login_slogin") && !body.contains("csrftoken"))
        if (!ok) {
            throw JwglxtException(loginErrorText(body))
        }
    }

    /**
     * 拉课表。
     *
     * @param year 学年起始年，2026 表示 2026-2027 学年
     * @param term 3 = 第一学期，12 = 第二学期，16 = 短学期
     */
    fun fetchTimetable(year: Int, term: Int): Timetable {
        val form = linkedMapOf("xnm" to year.toString(), "xqm" to term.toString())

        val candidates = buildList {
            kbcxPrefix?.let { add(it) }
            add("")
            add("/jwglxt")
        }.distinct()

        var json = ""
        for (p in candidates) {
            val (body, _) = runCatching {
                post("$base$p/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151", form, "$base$p/xtgl/initMenu.html")
            }.getOrNull() ?: continue
            if (body.contains("kbList") || body.trimStart().startsWith("{")) {
                json = body
                kbcxPrefix = p
                break
            }
        }
        if (json.isBlank()) {
            throw JwglxtException("登录后没拿到课表数据，可能账号没有课表权限，或者学期选错了")
        }

        val root = runCatching { JSONObject(json) }.getOrElse {
            throw JwglxtException("课表接口返回的数据看不懂，可能这个学校的版本不一样")
        }

        val list: JSONArray = root.optJSONArray("kbList") ?: JSONArray()
        val courses = ArrayList<Course>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            Course.fromJson(o)?.let { courses.add(it) }
        }

        val studentName = root.optJSONObject("xsxx")?.optString("XM") ?: ""

        if (courses.isEmpty()) {
            throw JwglxtException("这个学期没有查到课程，换一下学年试试？")
        }

        return Timetable(
            courses = courses,
            year = year,
            term = term,
            studentName = studentName,
        )
    }

    // ---------------------------------------------------------------- 内部

    private fun loginErrorText(body: String): String {
        val patterns = listOf(
            """id="tips"[^>]*>(.*?)</div>""",
            """id=["']tips["'][^>]*>([^<]{4,})""",
            """class="[^"]*(?:bg_danger|sl_danger|tips)[^"]*"[^>]*>(.*?)</div>""",
            """class="[^"]*(?:bg_danger|sl_danger|tips)[^"]*"[^>]*>([^<]{4,})""",
            """"message"\s*:\s*"([^"]+)"""",
            """"msg"\s*:\s*"([^"]+)"""",
        )
        for (p in patterns) {
            val hit = Regex(p, RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.trim()
            if (!hit.isNullOrBlank()) return hit
        }
        // 拿不到具体文案时，别只说「失败了」，把最可能的原因按顺序说清楚
        return "登录没通过：账号或密码不对。如果确认密码没错，可能是学校改了登录方式（比如要走统一身份认证）"
    }

    /**
     * 正方的前端用 jsbn 做 RSA，做法是 `hex2b64(rsaKey.encrypt(pwd))`：
     * 先用 RSA/ECB/PKCS1Padding 加密，再把**密文字节做 base64**。
     * 直接给十六进制串是不行的，服务端只会回「用户名或密码不正确」。
     */
    private fun rsaEncrypt(plain: String, modulusB64: String, exponentB64: String): String {
        val n = BigInteger(1, decodeAny(modulusB64))
        val e = BigInteger(1, decodeAny(exponentB64))
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e)) as RSAPublicKey
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encodeBase64(out)
    }

    /** 正方返回的 base64 有时带换行、有时是 hex 文本，统一处理 */
    private fun decodeAny(value: String): ByteArray {
        val cleaned = value.replace("\\s".toRegex(), "")
        val text = runCatching {
            String(decodeBase64(cleaned), Charsets.UTF_8)
        }.getOrNull()
        // 解出来是一串 hex 文本 → 按 hex 转字节
        if (text != null && text.length >= 2 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        return decodeBase64(cleaned)
    }

    private fun decodeBase64(s: String): ByteArray =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Base64.getDecoder().decode(s)
        } else {
            android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        }

    private fun encodeBase64(bytes: ByteArray): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Base64.getEncoder().encodeToString(bytes)
        } else {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }

    private fun get(url: String): String = decodeText(request(url, null, null, follow = true).first)

    private fun getBytes(url: String): ByteArray = request(url, null, null, follow = true).first

    private fun post(url: String, form: Map<String, String>, referer: String?): Pair<String, String> {
        val payload = form.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
        val (bytes, finalUrl) = request(url, payload.toByteArray(Charsets.UTF_8), referer, follow = true)
        return decodeText(bytes) to finalUrl
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    /**
     * 解码。不少学校的正方是 GBK 页面（响应头却写着 UTF-8），
     * 直接按 UTF-8 读会把「用户名或密码不正确」变成一堆问号。
     */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, charset("GB18030")) }.getOrDefault(utf8)
    }

    private fun request(
        url: String,
        body: ByteArray?,
        referer: String?,
        follow: Boolean,
    ): Pair<ByteArray, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = follow
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = if (body == null) "GET" else "POST"
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Accept", "text/html,application/json,*/*")
            jar.header()?.let { setRequestProperty("Cookie", it) }
            if (referer != null) setRequestProperty("Referer", referer)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }

        return try {
            if (body != null) conn.outputStream.use { it.write(body) }
            jar.store(conn.headerFields["Set-Cookie"])

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            val bytes = stream?.use { readAll(it) } ?: ByteArray(0)
            // 重定向链上可能还会下发 Set-Cookie
            jar.store(conn.headerFields["Set-Cookie"])
            bytes to conn.url.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(input: java.io.InputStream): ByteArray {
        val gz = if (input.markSupported()) input else java.io.BufferedInputStream(input)
        val bos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val stream = runCatching {
            // 正方有的服务器开 gzip
            val peek = java.io.PushbackInputStream(gz, 2)
            val header = ByteArray(2)
            val n = peek.read(header)
            if (n == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()) {
                GZIPInputStream(peek)
            } else {
                if (n > 0) peek.unread(header, 0, n)
                peek
            }
        }.getOrDefault(gz)
        stream.use {
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                bos.write(buffer, 0, n)
            }
        }
        return bos.toByteArray()
    }

    private companion object {
        /** 见过的两种部署：带 jwglxt 前缀 / 不带 */
        val PREFIX_CANDIDATES = listOf("", "/jwglxt")
    }
}

/** 极简 cookie 罐：正方整套流程只靠一个 JSESSIONID 串起会话 */
class CookieJar {
    private val map = LinkedHashMap<String, String>()

    fun store(headers: List<String>?) {
        headers ?: return
        for (raw in headers) {
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (value.isEmpty() || value == "deleted") map.remove(name) else map[name] = value
        }
    }

    fun header(): String? =
        if (map.isEmpty()) null else map.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun clear() = map.clear()
}

/**
 * 把用户粘进来的地址收拾干净。
 *
 * 很多人会直接粘课表页的地址，比如
 * `https://jw.example.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default`，
 * 这里自动截到域名部分，省得用户自己去删。
 */
private fun normalizeBase(raw: String): String {
    var b = raw.trim()
    if (b.isEmpty()) return ""
    if (!b.startsWith("http://", true) && !b.startsWith("https://", true)) {
        b = "http://$b"
    }
    // 去掉协议后面的路径 / 查询串
    val schemeEnd = b.indexOf("://") + 3
    val pathStart = b.indexOf('/', schemeEnd)
    if (pathStart > 0) b = b.substring(0, pathStart)
    return b.trimEnd('/')
}
