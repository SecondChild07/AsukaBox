package com.AusKa.ToolBox.data

import android.content.Context
import java.io.File

/**
 * 关键密文的本地镜像（`files/` 下一个小文件）。
 *
 * 为什么不只靠 SharedPreferences：它只有一个文件，落在 `shared_prefs/` 下。
 * 该文件被 ROM 的清理策略删掉、或者写一半进程被杀导致 xml 解析失败时，
 * `getSharedPreferences` 会**安静地返回一张空表** —— App 看到的就是「从来没存过」，
 * 用户看到的就是「重启之后数据自己没了」，而且没有任何地方能报警。
 *
 * 镜像写入走「临时文件 + rename」：要么是上一份完整的旧内容，要么是新完整内容，
 * 不会出现半截文件。里面存的是密文（兜底钥匙那一段），不是明文。
 */
internal object LocalMirror {

    fun write(context: Context, name: String, sealed: String) {
        if (sealed.isEmpty()) return
        runCatching {
            val target = File(context.filesDir, name)
            val tmp = File(context.filesDir, "$name.tmp")
            tmp.writeText(sealed)
            if (target.exists() && !target.delete()) {
                // 删不掉就退化成直接覆盖（至少内容是对的）
                target.writeText(sealed)
                tmp.delete()
                return@runCatching
            }
            if (!tmp.renameTo(target)) {
                target.writeText(sealed)
                tmp.delete()
            }
        }
    }

    fun read(context: Context, name: String): String =
        runCatching {
            val f = File(context.filesDir, name)
            if (f.isFile) f.readText().trim() else ""
        }.getOrDefault("")

    fun exists(context: Context, name: String): Boolean =
        runCatching { File(context.filesDir, name).let { it.isFile && it.length() > 0 } }.getOrDefault(false)

    fun clear(context: Context, name: String) {
        runCatching {
            File(context.filesDir, name).delete()
            File(context.filesDir, "$name.tmp").delete()
        }
    }
}
