package com.AusKa.ToolBox.chaoxing

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 把一段音频解码成 PCM，按秒数切成若干 WAV。
 *
 * 为什么需要：有些语音转写接口限制单次上传时长（智谱 GLM-ASR 只收 30 秒以内的文件），
 * 而听力作业的音频往往是 2~4 分钟。App 里不引 ffmpeg，只能用系统的 MediaExtractor +
 * MediaCodec 解成 PCM，再自己写 WAV 头切段。
 */
internal object AudioChunks {

    /** 单段最大秒数，留一点余量给接口那边的 30 秒限制 */
    private const val DEFAULT_MAX_SECONDS = 26

    /** 一段切好的音频：WAV 字节 + 它自己的采样率/声道（本地 ASR 重采样要用）*/
    class WavChunk(val wav: ByteArray, val sampleRate: Int, val channels: Int)

    /**
     * @return 每个元素是一个完整 WAV 文件字节；解码失败返回空列表（调用方回落到整文件上传）。
     */
    fun wavChunks(raw: ByteArray, maxSeconds: Int = DEFAULT_MAX_SECONDS): List<ByteArray> =
        chunkAudio(raw, maxSeconds).map { it.wav }

    /** 同 [wavChunks]，但把采样率/声道一起带出来（转成本地 ASR 的 16 kHz 单声道需要）*/
    fun chunkAudio(raw: ByteArray, maxSeconds: Int = DEFAULT_MAX_SECONDS): List<WavChunk> = runCatching {
        val tmp = File.createTempFile("cs_audio_", ".mp3")
        try {
            FileOutputStream(tmp).use { it.write(raw) }
            val pcm = decodeToPcm(tmp) ?: return emptyList()
            val bytesPerSecond =
                pcm.sampleRate * pcm.channels * 2 // 16-bit
            val chunkBytes = (bytesPerSecond * maxSeconds).coerceAtLeast(bytesPerSecond)
            val out = ArrayList<WavChunk>()
            var offset = 0
            while (offset < pcm.data.size) {
                val end = minOf(offset + chunkBytes, pcm.data.size)
                out.add(
                    WavChunk(
                        wrapWav(pcm.data.copyOfRange(offset, end), pcm.sampleRate, pcm.channels),
                        pcm.sampleRate,
                        pcm.channels,
                    )
                )
                offset = end
            }
            out
        } finally {
            tmp.delete()
        }
    }.getOrDefault(emptyList())

    /** 剥掉 WAV 的 44 字节文件头，留下裸 PCM（[wrapWav] 写的头是定长 44 字节）*/
    fun pcmOf(wav: ByteArray): ByteArray {
        if (wav.size <= WAV_HEADER) return ByteArray(0)
        // 认一下 RIFF 标记，免得以后改了头长度就悄悄错位
        val riff = wav.size > 12 && wav[0] == 'R'.code.toByte() && wav[1] == 'I'.code.toByte() &&
            wav[2] == 'F'.code.toByte() && wav[3] == 'F'.code.toByte()
        return wav.copyOfRange(if (riff) WAV_HEADER else 0, wav.size)
    }

    private const val WAV_HEADER = 44

    /** 把 WAV/PCM 片段转成 16 kHz 单声道 float 采样（本地 ASR 需要这个格式） */
    fun toMono16k(pcm: ByteArray, srcRate: Int, channels: Int): FloatArray {
        if (pcm.isEmpty() || srcRate <= 0 || channels <= 0) return FloatArray(0)
        val frames = pcm.size / (2 * channels)
        val mono = FloatArray(frames)
        var i = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                sum += ((hi shl 8) or lo).toShort().toFloat()
                i += 2
            }
            mono[f] = sum / channels / 32768f
        }
        if (srcRate == 16000) return mono
        val outLen = (frames.toLong() * 16000 / srcRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val step = srcRate.toDouble() / 16000.0
        for (n in 0 until outLen) {
            val pos = n * step
            val i0 = pos.toInt().coerceIn(0, frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val frac = (pos - i0).toFloat()
            out[n] = mono[i0] * (1 - frac) + mono[i1] * frac
        }
        return out
    }
    private class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int)

    private fun decodeToPcm(file: File): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = java.io.ByteArrayOutputStream()
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            // 先把两次 dequeue 的超时都设成 0：解码本身是微秒级的，
            // 用 10 ms 超时去等反而让整段解码卡在「等」上（实测 205 秒的 mp3 要 12 秒才解完）。
            // 只有两个口都暂时没数据时才 sleep 1 ms，避免空转烧 CPU。
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val ob = codec.getOutputBuffer(outIndex)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val chunk = ByteArray(info.size)
                    ob.get(chunk)
                    out.write(chunk)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
            return Pcm(out.toByteArray(), sampleRate, channels)
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun wrapWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val out = ByteArray(44 + pcm.size)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(s: String) = s.forEach { bb.put(it.code.toByte()) }
        ascii("RIFF")
        bb.putInt(36 + pcm.size)
        ascii("WAVE")
        ascii("fmt ")
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * 2)
        bb.putShort((channels * 2).toShort())
        bb.putShort(16)
        ascii("data")
        bb.putInt(pcm.size)
        bb.put(pcm)
        return out
    }
}
