package com.AusKa.ToolBox

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.AusKa.ToolBox.chaoxing.ImageInline
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * 题面图上传前的压缩：原图 1~3 MB 的 PNG，压完必须明显变小，且 MIME 得是 jpeg。
 * 这条直接影响答题速度（慢网络上传一张大 PNG 能卡好几秒）。
 */
@RunWith(AndroidJUnit4::class)
class ImageInlineTest {

    @Test
    fun bigPngGetsShrunk() {
        val bmp = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            color = Color.BLACK
            textSize = 40f
        }
        // 画点东西，免得纯白图压缩率失真
        for (i in 0 until 40) {
            canvas.drawText("Question $i  A. foo  B. bar  C. baz  D. qux", 20f, 60f + i * 38f, paint)
        }
        val png = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, png)
        bmp.recycle()

        val uri = ImageInline.dataUri(png.toByteArray())
        assertTrue("没生成 data uri", uri != null)
        assertTrue("MIME 不对: " + uri!!.take(30), uri.startsWith("data:image/jpeg;base64,"))

        val out = Base64.decode(uri.substringAfter("base64,"), Base64.DEFAULT)
        assertTrue("压完没变小：${out.size} vs ${png.size()}", out.size < png.size())
        println("image: ${png.size() / 1024} KB -> ${out.size / 1024} KB")
    }

    @Test
    fun emptyInputGivesNull() {
        assertTrue(ImageInline.dataUri(ByteArray(0)) == null)
    }
}
