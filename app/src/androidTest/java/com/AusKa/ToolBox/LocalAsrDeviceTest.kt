package com.AusKa.ToolBox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.AusKa.ToolBox.chaoxing.AudioChunks
import com.AusKa.ToolBox.chaoxing.LocalAsr
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 真机/模拟器上的自测：so 能不能加载、模型能不能下、下了能不能真的转出字。
 *
 * 这是唯一能证明「本地转写不是纸面功能」的地方 —— 单元测试跑在 JVM 上，碰不到 JNI。
 *
 * 跑法：
 *   ./gradlew :app:connectedDebugAndroidTest --tests '*LocalAsrDeviceTest*'
 * 第一次会下载 228 MB 模型（走 App 里同一套下载逻辑），之后复用。
 *
 * 方法名不用中文反引号：androidTest 编到 dex 里，dex 040 之前不允许方法名带空格，
 * 会直接 dexBuilderDebugAndroidTest 失败（单测跑在 JVM 上，没这个限制）。
 */
@RunWith(AndroidJUnit4::class)
class LocalAsrDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeLibraryLoads() {
        System.loadLibrary("sherpa-onnx-jni")
    }

    @Test
    fun downloadAndTranscribeEnglish() {
        // 1. 模型：没有就按 App 里那条路下载（顺便验证 sha256 校验和镜像地址）
        if (!LocalAsr.isReady(ctx)) {
            val ok = LocalAsr.prepare(ctx) { msg -> println("LocalAsr: $msg") }
            assertTrue("模型下载失败：" + LocalAsr.error(), ok)
        }
        assertTrue("模型文件不齐", LocalAsr.isReady(ctx))

        // 2. 官方自带的英文测试音频（0.2 MB）
        val wav = File(ctx.cacheDir, "asr-test-en.wav")
        if (!wav.isFile || wav.length() < 1000) {
            val url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17" +
                "/resolve/main/test_wavs/en.wav"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            conn.inputStream.use { input -> wav.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
        }
        assertTrue("测试音频没下下来", wav.length() > 1000)

        // 3. 走 App 里同一条预处理：剥 WAV 头 → 16 kHz 单声道 → 推理
        val samples = AudioChunks.toMono16k(AudioChunks.pcmOf(wav.readBytes()), 16_000, 1)
        assertTrue("采样点太少：" + samples.size, samples.size > 8_000)

        val text = LocalAsr.transcribe(ctx, samples)
        println("LocalAsr 转写结果：$text")
        assertTrue("没转出文字：" + LocalAsr.error(), text.isNotBlank())
        assertTrue("转出来不是英文：" + text, text.any { it.isLetter() })
    }

    /**
     * 「从本地文件导入模型」这条路：把 sensevoice-model.zip 放进 filesDir，
     * 先清空已有模型，再走一遍导入 + 校验 + 推理。
     *
     * 准备方式（跑之前手动做）：
     *   adb push sensevoice-model.zip /data/local/tmp/
     *   adb shell "cat /data/local/tmp/sensevoice-model.zip | run-as com.AusKa.ToolBox sh -c 'cat > files/import-test.zip'"
     */
    @Test
    fun importZipAndTranscribe() {
        val zip = File(ctx.filesDir, "import-test.zip")
        org.junit.Assume.assumeTrue("filesDir 里没有 import-test.zip，跳过", zip.isFile)

        LocalAsr.clear(ctx)
        assertTrue("清理后不该还是就绪状态", !LocalAsr.isReady(ctx))

        val result = LocalAsr.importFrom(ctx, listOf(android.net.Uri.fromFile(zip)))
        println("导入结果：$result")
        assertTrue("导入后模型没就绪：$result", LocalAsr.isReady(ctx))

        // 导入进来的模型要真能推理，不能只是文件躺在那儿
        val wav = File(ctx.cacheDir, "asr-test-en.wav")
        org.junit.Assume.assumeTrue("没有测试音频，跳过推理校验", wav.isFile)
        val samples = AudioChunks.toMono16k(AudioChunks.pcmOf(wav.readBytes()), 16_000, 1)
        val text = LocalAsr.transcribe(ctx, samples)
        println("导入后转写结果：$text")
        assertTrue("导入的模型转不出字：" + LocalAsr.error(), text.isNotBlank())
    }

    /**
     * 胖包（-PWITH_ASR=true）自带模型：不联网，直接从 assets 解压出来就能用。
     * 瘦包上会自动跳过。
     */
    @Test
    fun releaseBundledModel() {
        org.junit.Assume.assumeTrue("这个安装包里没带模型，跳过", LocalAsr.bundled(ctx))

        LocalAsr.clear(ctx)
        assertTrue("清空后不该还是就绪", !LocalAsr.isReady(ctx))

        val started = System.currentTimeMillis()
        val ok = LocalAsr.prepare(ctx) { println("准备：$it") }
        val cost = System.currentTimeMillis() - started
        println("释放包内模型耗时 ${cost}ms，结果=" + ok + "，状态=" + LocalAsr.lastNote())
        assertTrue("释放失败：" + LocalAsr.error(), ok)
        assertTrue("释放后模型没就绪", LocalAsr.isReady(ctx))

        val wav = File(ctx.cacheDir, "asr-test-en.wav")
        org.junit.Assume.assumeTrue("没有测试音频，跳过推理校验", wav.isFile)
        val samples = AudioChunks.toMono16k(AudioChunks.pcmOf(wav.readBytes()), 16_000, 1)
        val text = LocalAsr.transcribe(ctx, samples)
        println("包内模型转写结果：$text")
        assertTrue("包内模型转不出字：" + LocalAsr.error(), text.isNotBlank())
    }
}
