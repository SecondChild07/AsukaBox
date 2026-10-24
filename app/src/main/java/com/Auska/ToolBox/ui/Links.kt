package com.Auska.ToolBox.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 用系统浏览器打开链接。
 *
 * 包名校验之后 `queries` 里只声明了支付宝，所以这里不能先 `resolveActivity`，
 * 直接发 Intent + `runCatching` 兜住「没有浏览器」的情况。
 */
internal fun openLink(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}
