package com.AusKa.ToolBox.chaoxing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 手机本地语音转写（sherpa-onnx + SenseVoice）。
 *
 * 为什么不用云端：听力题的音频要发到第三方 STT 服务才有文字，等于把用户的学习内容
 * 交给别人；而且 DeepSeek 这类接口根本不带转写。这里把模型放到手机上，断网也能做。
 *
 * 模型：sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17（int8 量化，约 228 MB）。
 * 用 SenseVoice 而不是交接文档里说的 paraformer-zh-small，是因为验收基准是**四级听力**
 * （英文音频），Paraformer 中文小模型对英文基本不通；SenseVoice 一份模型同时认中英日韩粤。
 *
 * 分发方式：不打进 APK（包体从 13 MB 涨到 200 MB+），首次用到时从 hf-mirror 下载到
 * `filesDir/asr/`，下完校验 SHA-256，校验不过直接删掉重下。
 *
 * 注意：模型文件不是机密，但也不需要备份，别往 git 里塞。
 */
internal object LocalAsr {

    /** 16 kHz 单声道是 sherpa-onnx 唯一接受的输入格式 */
    const val SAMPLE_RATE = 16000

    private const val DIR_NAME = "asr"

    /** 镜像站；GitHub Releases 在部分网络下拿不到，走 HF 的国内镜像，官方源留作兜底 */
    private const val MIRROR = "https://hf-mirror.com"

    /**
     * 下载源，按顺序试。hf-mirror 对同一出口 IP 反复拉 228 MB 会回 429，
     * 这时候退到官方源（国内多半不通，但挂了代理或海外网络就能救回来）。
     */
    private val MIRRORS = listOf(
        MIRROR,
        "https://huggingface.co",
    )

    private const val REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"

    /**
     * 自带模型的 APK 里，zip 放在 assets 的这个位置。
     * 打包开关看 app/build.gradle.kts 的 WITH_ASR（默认不开，开了包体从 53 MB 涨到 280 MB 左右）。
     */
    private const val BUNDLED_ASSET = "asr/sensevoice-model.zip"

    private class ModelFile(val name: String, val bytes: Long, val sha256: String)

    /**
     * 下载清单。bytes 用来判断「文件是不是完整的」（避免半截文件被当成已就绪），
     * sha256 只在刚下完那一刻校验一次，之后的就绪判断只看大小，不然每次启动都要算 228 MB。
     *
     * 值取自 2024-07-17 版仓库；上游若换过模型文件，这两个值要一起改，
     * 否则下载会被判为「校验失败」（这是故意的：宁可失败也不装一个来路不明的模型）。
     */
    private val FILES = listOf(
        ModelFile(
            "model.int8.onnx",
            239_233_841L,
            "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
        ),
        ModelFile(
            "tokens.txt",
            315_894L,
            "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
        ),
    )

    @Volatile
    var downloading: Boolean = false
        private set

    /** 0f..1f，只在 [downloading] 时有意义 */
    @Volatile
    var progress: Float = 0f
        private set

    @Volatile
    private var lastError: String = ""

    /** 给界面轮询的一句话状态（下载中 / 导入中 / 导入结果）*/
    @Volatile
    private var statusText: String = ""

    fun lastNote(): String = statusText

    /** 对话框每次打开时清掉上一次的结果，免得把「已导入」当成刚发生的事 */
    fun resetNote() {
        statusText = ""
        lastError = ""
    }

    /**
     * 推理线程数。SenseVoice int8 在 4 个大核上大约比 2 线程快 1.5~2 倍；
     * 再往上加在大小核调度上会互相抢，反而更慢。测试里可以临时改小做对比。
     */
    @Volatile
    var threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        set(value) {
            field = value.coerceIn(1, 8)
            // 改了线程数必须重建 recognizer 才生效
            releaseRecognizer()
        }

    private var recognizer: OfflineRecognizer? = null
    private val lock = Any()
    private var announcedReady = false

    // ------------------------------------------------------------------ 状态

    fun modelDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * 安装包里是不是自带模型（用 `-PWITH_ASR=true` 打包时会把 zip 塞进 assets）。
     * 只 open 一下不读内容，很便宜，可以在 UI 线程调。
     */
    fun bundled(context: Context): Boolean = runCatching {
        context.assets.open(BUNDLED_ASSET).close()
        true
    }.getOrDefault(false)

    /** 模型文件的总体积（MB），给设置页显示用 */
    fun sizeMb(): Long = FILES.sumOf { it.bytes } / 1024 / 1024

    /** 文件齐了没（只看大小，够快，可以在 UI 线程调用）*/
    fun isReady(context: Context): Boolean = readyIn(modelDir(context))

    fun readyIn(dir: File): Boolean = FILES.all { f ->
        val file = File(dir, f.name)
        file.isFile && file.length() > 0 && (f.bytes <= 0 || file.length() == f.bytes)
    }

    /** 还缺哪些文件，给设置页一句话说明 */
    fun missing(context: Context): List<String> {
        val dir = modelDir(context)
        return FILES.filterNot { File(dir, it.name).isFile }
            .map { it.name }
    }

    fun error(): String = lastError

    // ------------------------------------------------------------------ 下载

    /**
     * 确保模型就位：缺什么下什么。耗时（228 MB 在手机上大概 1~5 分钟），
     * **必须在后台线程调用**；同一时刻只允许一个下载。
     *
     * @param onProgress 阶段性提示（给「转写 3/8 段」那种进度条文案用）
     */
    fun prepare(context: Context, onProgress: ((String) -> Unit)? = null): Boolean {
        synchronized(lock) {
            val dir = modelDir(context)
            if (readyIn(dir)) return true
            if (downloading) return false
            downloading = true
            progress = 0f
            lastError = ""
            return try {
                dir.mkdirs()
                // 包里自带模型就先解压出来，完全不碰网络（解压 228 MB 在手机上一两秒）
                if (bundled(context)) {
                    statusText = "正在释放安装包里的模型…"
                    onProgress?.invoke("正在释放安装包里的模型…")
                    val total = FILES.sumOf { it.bytes }.coerceAtLeast(1L)
                    val (okCount, badCount) = context.assets.open(BUNDLED_ASSET).use { input ->
                        importZipStream(input, dir) { read ->
                            progress = (read.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                    progress = 1f
                    if (readyIn(dir)) {
                        statusText = "已释放包内模型"
                        return true
                    }
                    statusText = "包内模型不完整（成功 $okCount / 失败 $badCount），改走在线下载"
                }
                val todo = FILES.filterNot { f ->
                    val file = File(dir, f.name)
                    file.isFile && f.bytes > 0 && file.length() == f.bytes
                }
                var doneBytes = FILES.sumOf { it.bytes } - todo.sumOf { it.bytes }
                val totalBytes = FILES.sumOf { it.bytes }.coerceAtLeast(1L)
                for (f in todo) {
                    onProgress?.invoke("正在下载语音模型 " + doneBytes / 1048576 + "/" + totalBytes / 1048576 + " MB")
                    download(f, File(dir, f.name)) { read ->
                        progress = ((doneBytes + read).toFloat() / totalBytes).coerceIn(0f, 1f)
                    }
                    doneBytes += f.bytes
                }
                progress = 1f
                statusText = if (readyIn(dir)) "模型下载完成" else "模型下载不完整"
                readyIn(dir)
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                statusText = "下载失败：" + lastError
                onProgress?.invoke("语音模型下载失败：" + lastError)
                false
            } finally {
                downloading = false
            }
        }
    }

    private fun urlOf(f: ModelFile, mirror: String): String = "$mirror/$REPO/resolve/main/${f.name}"

    /**
     * 依次试每个源，每个源退避重试两次（429 限流时很常见），全都不行才把错误抛给调用方
     * （界面会显示「下载失败：HTTP 429」）。
     */
    private fun download(f: ModelFile, dest: File, onBytes: (Long) -> Unit) {
        var lastError: Throwable? = null
        for (mirror in MIRRORS) {
            for (attempt in 0 until 2) {
                try {
                    downloadOnce(urlOf(f, mirror), f, dest, onBytes)
                    return
                } catch (e: Throwable) {
                    lastError = e
                    val msg = (e as? IOException)?.message?.trim().orEmpty()
                    val retryable = msg.isEmpty() || msg.startsWith("HTTP 429") || msg.startsWith("HTTP 5")
                    if (!retryable) throw e
                    if (attempt == 0) {
                        try {
                            Thread.sleep(4_000)
                        } catch (_: InterruptedException) {
                            throw e
                        }
                    }
                }
            }
        }
        throw lastError ?: IOException("下载失败")
    }

    private fun downloadOnce(url: String, f: ModelFile, dest: File, onBytes: (Long) -> Unit) {
        val part = File(dest.absolutePath + ".part")
        part.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AsukaBox")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var notifyAt = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        // 每 2 MB 回调一次，别把主线程刷爆
                        if (read - notifyAt >= 2L * 1024 * 1024) {
                            notifyAt = read
                            onBytes(read)
                        }
                    }
                }
            }
            onBytes(read)
            if (f.bytes > 0 && read != f.bytes) throw IOException("${f.name} 大小不对（$read）")
            if (f.sha256.isNotEmpty()) {
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(f.sha256, ignoreCase = true)) {
                    throw IOException("${f.name} 校验失败")
                }
            }
            place(part, dest)
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 先删旧的再改名，避免出现「大小对不上」的中间态。
     * 注意别在改名之前删 .part —— 那样 copyTo 兜底也会因为源文件没了而失败。
     */
    private fun place(part: File, dest: File) {
        dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    }

    // ------------------------------------------------------------------ 本地导入

    /**
     * 用户自己把模型从电脑拷到手机上，这里导进来 —— 给「镜像限流 / 手机流量紧张」留的后路。
     *
     * 支持两种选法：
     *  - 直接选 `model.int8.onnx` 和 `tokens.txt` 两个文件（可多选）；
     *  - 选一个 zip（内部层级随意，按文件名匹配）。
     *
     * 校验和下载走同一套（大小 + SHA-256），不匹配就丢弃，免得把别的模型当这个用。
     *
     * @return 给用户看的一句话结果
     */
    fun importFrom(context: Context, uris: List<Uri>): String {
        synchronized(lock) {
            if (uris.isEmpty()) return "没有选中文件"
            val dir = modelDir(context)
            dir.mkdirs()
            var ok = 0
            var bad = 0
            statusText = "正在导入模型…"
            try {
                for (uri in uris) {
                    val name = displayName(context, uri)
                    if (name.endsWith(".zip", ignoreCase = true)) {
                        val r = importZip(context, uri, dir)
                        ok += r.first
                        bad += r.second
                    } else {
                        val spec = FILES.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        if (spec == null) {
                            bad++
                        } else if (copyVerified(context, uri, spec, dir)) {
                            ok++
                        } else {
                            bad++
                        }
                    }
                }
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                statusText = "导入失败：" + lastError
                return statusText
            }
            val ready = readyIn(dir)
            statusText = when {
                ready -> "已导入模型，可以断网转写了"
                ok == 0 -> "没认出模型文件（需要 model.int8.onnx 和 tokens.txt，或打包好的 zip）"
                else -> "导入不完整（成功 $ok 个、失败 $bad 个），还缺：" + missing(context).joinToString("、")
            }
            return statusText
        }
    }

    private fun copyVerified(context: Context, uri: Uri, spec: ModelFile, dir: File): Boolean {
        val part = File(dir, spec.name + ".part")
        part.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(part).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    // 超过预期大小就别再写了（既防呆也防挑错文件把磁盘塞满）
                    if (read + n > spec.bytes) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    read += n
                }
            }
        } ?: return false
        val ok = matches(part, spec, read, digest)
        if (ok) place(part, File(dir, spec.name)) else part.delete()
        return ok
    }

    private fun importZip(context: Context, uri: Uri, dir: File): Pair<Int, Int> {
        val input = context.contentResolver.openInputStream(uri) ?: return 0 to 0
        return input.use { importZipStream(it, dir, null) }
    }

    /**
     * 从任意输入流里解模型（用户选的 zip、或者安装包 assets 里的 zip，共用这一条）。
     * 只认文件名对得上的条目，层级随意；大小/SHA-256 不对就丢弃。
     */
    private fun importZipStream(
        raw: java.io.InputStream,
        dir: File,
        onBytes: ((Long) -> Unit)?,
    ): Pair<Int, Int> {
        var ok = 0
        var bad = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(raw)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val base = entry.name.substringAfterLast('/')
                    val spec = FILES.firstOrNull { it.name.equals(base, ignoreCase = true) }
                    if (spec != null) {
                        val part = File(dir, spec.name + ".part")
                        part.delete()
                        val digest = MessageDigest.getInstance("SHA-256")
                        var read = 0L
                        FileOutputStream(part).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                if (read + n > spec.bytes) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                read += n
                                onBytes?.invoke(total + read)
                            }
                        }
                        if (matches(part, spec, read, digest)) {
                            place(part, File(dir, spec.name))
                            ok++
                        } else {
                            part.delete()
                            bad++
                        }
                        total += read
                    }
                }
                zip.closeEntry()
            }
        }
        return ok to bad
    }

    private fun matches(part: File, spec: ModelFile, read: Long, digest: MessageDigest): Boolean {
        if (!part.isFile) return false
        if (spec.bytes > 0 && read != spec.bytes) return false
        if (spec.sha256.isEmpty()) return true
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        return got.equals(spec.sha256, ignoreCase = true)
    }

    private fun displayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: uri.lastPathSegment.orEmpty()
        } catch (_: Throwable) {
            uri.lastPathSegment.orEmpty()
        }
    }

    /** 用户点「删除模型」时用：连 recognizer 一起释放，否则文件删了内存里还攥着句柄 */
    fun clear(context: Context) {
        synchronized(lock) {
            releaseRecognizer()
            announcedReady = false
            modelDir(context).deleteRecursively()
            progress = 0f
            lastError = ""
            statusText = ""
        }
    }

    // ------------------------------------------------------------------ 推理

    /**
     * 16 kHz 单声道 float 采样 → 文本。模型没准备好或空输入返回空串（调用方按「没转到」处理）。
     */
    fun transcribe(context: Context, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val rec = recognizer(context) ?: return ""
        return synchronized(rec) {
            var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
            try {
                stream = rec.createStream()
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                ""
            } finally {
                runCatching { stream?.release() }
            }
        }
    }

    private fun recognizer(context: Context): OfflineRecognizer? {
        synchronized(lock) {
            recognizer?.let { return it }
            if (!isReady(context)) return null
            val dir = modelDir(context)
            return try {
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = File(dir, "model.int8.onnx").absolutePath,
                            language = "auto",
                            useInverseTextNormalization = true,
                        ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        // 明说模型类型，省掉 sherpa 猜模型（猜错要加载两遍，首包慢一倍）
                        modelType = "sense_voice",
                        numThreads = threads,
                        debug = false,
                        provider = "cpu",
                    ),
                    decodingMethod = "greedy_search",
                )
                // assetManager 必须显式传 null：模型文件在 filesDir 里是绝对路径，
                // 传了 AssetManager 的话 sherpa 会先去 assets 里找，找不到直接 abort（进程崩溃，
                // 不是抛异常，try/catch 兜不住）—— 这是实测踩到的坑
                OfflineRecognizer(assetManager = null, config = config).also {
                    recognizer = it
                    if (!announcedReady) announcedReady = true
                }
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                null
            }
        }
    }

    fun releaseRecognizer() {
        synchronized(lock) {
            runCatching { recognizer?.release() }
            recognizer = null
        }
    }
}
