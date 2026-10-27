package com.AusKa.ToolBox.chaoxing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 把题面图片转成能塞进 chat/completions 的 data URI。
 *
 * 为什么要重编码：超星 CDN 上的题面常是 1~3 MB 的 PNG，一张原样 base64 之后体积再涨 33%，
 * 上传慢（尤其手机网络差的时候）还容易被服务端按大小拒掉。题面只是文字和线框，
 * 缩到最长边 1280、JPEG 82 之后通常只有几十 KB，识别效果几乎没差别。
 */
internal object ImageInline {

    private const val MAX_SIDE = 1280
    private const val QUALITY = 82

    fun dataUri(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val scaled = runCatching { shrink(bytes) }.getOrNull()
        if (scaled != null && scaled.isNotEmpty()) {
            return "data:image/jpeg;base64," + Base64.encodeToString(scaled, Base64.NO_WRAP)
        }
        // 解不出来（少见格式 / 内存不足）就按原始字节发，mime 至少别写错
        return "data:" + mimeOf(bytes) + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun shrink(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= MAX_SIDE || h / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val longSide = maxOf(decoded.width, decoded.height)
        val target = if (longSide > MAX_SIDE) {
            val ratio = MAX_SIDE.toFloat() / longSide
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (target !== decoded) decoded.recycle()
        target.recycle()
        return out.toByteArray()
    }

    /** 只在重编码失败时兜底用，免得给模型一个 mime 对不上的 data URI */
    private fun mimeOf(bytes: ByteArray): String {
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            bytes.size > 8 && at(0) == 0x89 && at(1) == 0x50 -> "image/png"
            bytes.size > 4 && at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            bytes.size > 12 && at(0) == 0x47 && at(1) == 0x49 -> "image/gif"
            bytes.size > 12 && at(8) == 0x57 && at(9) == 0x45 -> "image/webp"
            else -> "image/png"
        }
    }
}
