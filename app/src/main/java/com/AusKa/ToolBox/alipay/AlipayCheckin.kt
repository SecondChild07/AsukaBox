package com.AusKa.ToolBox.alipay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import com.AusKa.ToolBox.security.Crypt

/**
 * 拉起支付宝签到。
 *
 * Main.js 的做法值得保留：不是简单 ACTION_VIEW，而是伪造一条 NFC 标签被扫到的广播
 * （ACTION_NDEF_DISCOVERED + NDEF_MESSAGES + Alipay 的 application record），
 * 支付宝收到后直接走「贴卡签到」的入口，比裸 URL 更稳。
 *
 * 包名不落明文，运行时解出来。
 */
object AlipayCheckin {

    private val ALIPAY_PKG: String get() = Crypt.decode(Crypt.ALIPAY)

    fun launch(activity: Activity, url: String): Result {
        if (url.isBlank()) return Result.NoUrl

        val viaNdef = runCatching {
            val records = arrayOf(
                NdefRecord.createUri(url),
                NdefRecord.createApplicationRecord(ALIPAY_PKG),
            )
            val intent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                data = Uri.parse(url)
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(NdefMessage(records)))
                setPackage(ALIPAY_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        }.isSuccess

        if (viaNdef) return Result.Success

        // 退路：普通 VIEW
        val viaView = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        }
        return when {
            viaView.isSuccess -> Result.Success
            viaView.exceptionOrNull() is ActivityNotFoundException -> Result.NoAlipay
            else -> Result.Failed
        }
    }

    enum class Result { Success, NoUrl, NoAlipay, Failed }
}
