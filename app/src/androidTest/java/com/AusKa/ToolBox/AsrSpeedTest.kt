package com.AusKa.ToolBox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.AusKa.ToolBox.chaoxing.AudioChunks
import com.AusKa.ToolBox.chaoxing.LocalAsr
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 本地转写的速度/内存实测：同一段真实听力音频，跑 2 线程 vs 4 线程。
 *
 * 准备：adb push 一段 mp3 到 /data/local/tmp/，再 run-as 拷成 files/speed-test.mp3
 */
@RunWith(AndroidJUnit4::class)
class AsrSpeedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun compareThreadsOnRealAudio() {
        val mp3 = File(ctx.filesDir, "speed-test.mp3")
        assumeTrue("files/speed-test.mp3 不存在，跳过", mp3.isFile)
        assumeTrue("模型没就绪，跳过", LocalAsr.isReady(ctx))

        val bytes = mp3.readBytes()
        val t0 = System.currentTimeMillis()
        // 本地路径用的就是 30 秒一段
        val chunks = AudioChunks.chunkAudio(bytes, 30)
        val decodeMs = System.currentTimeMillis() - t0
        assertTrue("切段失败", chunks.isNotEmpty())
        val audioSec = chunks.sumOf { it.wav.size / (it.sampleRate * it.channels * 2) }

        // 预热：第一次会加载模型，别算进耗时
        run {
            val c = chunks.first()
            LocalAsr.threads = 4
            LocalAsr.transcribe(ctx, AudioChunks.toMono16k(AudioChunks.pcmOf(c.wav), c.sampleRate, c.channels))
        }

        for (n in listOf(2, 4)) {
            LocalAsr.threads = n
            val rt = Runtime.getRuntime()
            val before = rt.totalMemory() - rt.freeMemory()
            var peak = 0L
            val start = System.currentTimeMillis()
            var chars = 0
            for (c in chunks) {
                val samples = AudioChunks.toMono16k(AudioChunks.pcmOf(c.wav), c.sampleRate, c.channels)
                chars += LocalAsr.transcribe(ctx, samples).length
                val used = rt.totalMemory() - rt.freeMemory()
                if (used - before > peak) peak = used - before
            }
            val cost = System.currentTimeMillis() - start
            val rtf = cost / 1000.0 / audioSec
            println(
                "ASR threads=$n 音频=${audioSec}s 耗时=${cost}ms " +
                    "RTF=${"%.2f".format(rtf)} 字符=$chars 峰值增量=${peak / 1048576}MB 解码=${decodeMs}ms"
            )
        }
        // 恢复默认，免得影响别的测试
        LocalAsr.threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
