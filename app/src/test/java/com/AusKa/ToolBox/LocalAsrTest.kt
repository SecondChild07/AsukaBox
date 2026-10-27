package com.AusKa.ToolBox

import com.AusKa.ToolBox.chaoxing.AudioChunks
import com.AusKa.ToolBox.chaoxing.LocalAsr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 本地 ASR 的输入格式契约。
 *
 * sherpa-onnx 只认 16 kHz 单声道 float，喂错格式不会报错，只会返回一串乱码 ——
 * 所以这几条断言是「听力题能不能转出人话」的地基，别删。
 */
class LocalAsrTest {

    /** 造一段 16-bit PCM：frames 个采样点，每声道同值 */
    private fun pcm(frames: Int, channels: Int, value: Short = 1000): ByteArray {
        val buf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            repeat(channels) { buf.putShort(value) }
        }
        return buf.array()
    }

    @Test
    fun `44点1k 双声道 1 秒重采样成 16000 个采样点`() {
        val src = pcm(44_100, 2)
        val out = AudioChunks.toMono16k(src, 44_100, 2)
        assertEquals(16_000, out.size)
    }

    @Test
    fun `48k 单声道 2 秒按比例压到 32000 点`() {
        val src = pcm(96_000, 1)
        val out = AudioChunks.toMono16k(src, 48_000, 1)
        assertEquals(32_000, out.size)
    }

    @Test
    fun `本来就是 16k 单声道时不做多余插值`() {
        val src = pcm(16_000, 1)
        val out = AudioChunks.toMono16k(src, 16_000, 1)
        assertEquals(16_000, out.size)
        // 1000 / 32768 ≈ 0.0305，插值不应把幅度改掉
        assertEquals(1000f / 32768f, out[123], 1e-6f)
    }

    @Test
    fun `空输入和非法参数返回空数组而不是崩`() {
        assertEquals(0, AudioChunks.toMono16k(ByteArray(0), 44_100, 2).size)
        assertEquals(0, AudioChunks.toMono16k(pcm(100, 2), 0, 2).size)
        assertEquals(0, AudioChunks.toMono16k(pcm(100, 2), 44_100, 0).size)
    }

    @Test
    fun `左右声道会被平均成单声道`() {
        // L = 16384, R = -16384 → 平均正好 0
        val buf = ByteBuffer.allocate(4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2) {
            buf.putShort(16384)
            buf.putShort((-16384).toShort())
        }
        val out = AudioChunks.toMono16k(buf.array(), 16_000, 2)
        assertEquals(2, out.size)
        assertEquals(0f, out[0], 1e-6f)
    }

    @Test
    fun `WAV 头会被剥掉，只留裸 PCM`() {
        val body = pcm(100, 1)
        val wav = ByteBuffer.allocate(44 + body.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + body.size)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(16_000)
            putInt(32_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(body.size)
            put(body)
        }.array()
        val stripped = AudioChunks.pcmOf(wav)
        assertEquals(body.size, stripped.size)
        assertTrue(body.contentEquals(stripped))
    }

    @Test
    fun `模型文件不全时不算就绪`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "asr-test-" + System.nanoTime())
        dir.mkdirs()
        try {
            assertFalse(LocalAsr.readyIn(dir))
            // 有同名文件但大小不对（半截下载）也不能算就绪
            File(dir, "model.int8.onnx").writeBytes(ByteArray(1024))
            File(dir, "tokens.txt").writeBytes(ByteArray(16))
            assertFalse(LocalAsr.readyIn(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `模型体积报给用户的是 200 MB 级`() {
        // 设置页要拿它显示「约 xxx MB」，别出现 0 或者负数
        assertTrue(LocalAsr.sizeMb() in 200..260)
    }
}
