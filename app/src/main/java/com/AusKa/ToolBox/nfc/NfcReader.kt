package com.AusKa.ToolBox.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * NFC 只读解析（Main.js 的 describeTag / readRawNdef / parseNdefTlv 移植）。
 *
 * 先用系统的 Ndef 技术读；读不到（空卡、非标准私卡、系统不给 Ndef 技术）就退回逐页原始读取：
 * MifareUltralight.readPages，或者 NfcA READ 0x30，然后自己解析 NDEF TLV。
 */
class NfcReader(private val activity: Activity) {

    data class Reading(
        val uid: String,
        val detail: String,
        val urls: List<String>,
        val at: Long = System.currentTimeMillis(),
    )

    enum class Status { Unsupported, Disabled, Ready }

    var onReading: ((Reading) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var adapter: NfcAdapter? = null
    private var readerOn = false
    private var lastUid = ""
    private var lastAt = 0L

    private val callback = NfcAdapter.ReaderCallback { tag -> handleTag(tag) }

    private val flags = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    fun status(): Status {
        val a = adapter ?: runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull()
        adapter = a ?: return Status.Unsupported
        return if (a.isEnabled) Status.Ready else Status.Disabled
    }

    fun start(): Status {
        val st = status()
        if (st != Status.Ready) return st
        if (!readerOn) {
            runCatching {
                adapter?.enableReaderMode(activity, callback, flags, null)
                readerOn = true
            }.onFailure { Log.w(TAG, "enableReaderMode failed: ${it.message}") }
        }
        return st
    }

    fun stop() {
        if (!readerOn) return
        runCatching { adapter?.disableReaderMode(activity) }
        readerOn = false
    }

    private fun handleTag(tag: Tag) {
        val uid = runCatching { tag.id.toHex() }.getOrDefault("")
        val now = System.currentTimeMillis()
        if (uid.isNotEmpty() && uid == lastUid && now - lastAt < DEBOUNCE_MS) return
        lastUid = uid
        lastAt = now

        val reading = runCatching { describe(tag) }.getOrElse {
            Reading(uid, "读卡异常: ${it.message}", emptyList())
        }
        main.post { onReading?.invoke(reading) }
    }

    private fun describe(tag: Tag): Reading {
        val lines = ArrayList<String>()
        val urls = ArrayList<String>()

        lines += "UID: " + (tag.id.toHex().ifEmpty { "(无)" })
        lines += "Tech: " + tag.techList.joinToString()

        val ndef = runCatching { Ndef.get(tag) }.getOrNull()
        if (ndef != null) {
            val ok = runCatching {
                ndef.connect()
                try {
                    val msg = ndef.ndefMessage ?: runCatching { ndef.cachedNdefMessage }.getOrNull()
                    if (msg == null) {
                        lines += "NDEF 为空（空白卡）"
                    } else {
                        val (l, u) = recordsToResult(msg.records)
                        lines += l
                        urls += u
                    }
                } finally {
                    // 放 finally：中途抛异常也要断开，否则 Tag 一直挂着，下次读卡报 I/O error / Tag lost
                    runCatching { ndef.close() }
                }
            }
            if (ok.isSuccess) {
                return Reading(tag.id.toHex(), lines.joinToString("\n"), urls)
            }
            lines += "NDEF 读取失败: ${ok.exceptionOrNull()?.message}，转原始读取…"
        } else {
            lines += "系统未提供 Ndef 技术，转原始逐页读取…"
        }

        val raw = readRawNdef(tag)
        lines += "原始读取: ${raw.note}"
        if (raw.message != null) {
            val (l, u) = recordsToResult(raw.message.records)
            lines += l
            urls += u
        } else if (raw.hex.isNotEmpty()) {
            lines += "原始数据: ${raw.hex}"
        }

        return Reading(tag.id.toHex(), lines.joinToString("\n"), urls)
    }

    private class RawResult(val message: NdefMessage?, val hex: String, val note: String)

    private fun readRawNdef(tag: Tag): RawResult {
        val data = ArrayList<Byte>()
        var readerName = ""
        val mu = runCatching { MifareUltralight.get(tag) }.getOrNull()
        val nfcA = runCatching { NfcA.get(tag) }.getOrNull()

        try {
            if (mu != null) {
                readerName = "MifareUltralight"
                mu.connect()
                var page = 4
                while (page <= RAW_MAX_PAGE) {
                    val chunk = runCatching { mu.readPages(page) }.getOrNull() ?: break
                    chunk.take(16).forEach { data.add(it) }
                    if (parseNdefTlv(data) != null) break
                    page += 4
                }
                runCatching { mu.close() }
            } else if (nfcA != null) {
                readerName = "NfcA READ(0x30)"
                nfcA.connect()
                var page = 4
                while (page <= RAW_MAX_PAGE) {
                    val resp = runCatching { nfcA.transceive(byteArrayOf(0x30, page.toByte())) }.getOrNull()
                    if (resp == null || resp.size < 16) break
                    resp.take(16).forEach { data.add(it) }
                    if (parseNdefTlv(data) != null) break
                    page += 4
                }
                runCatching { nfcA.close() }
            } else {
                return RawResult(null, "", "该卡不支持逐页读取")
            }
        } catch (e: Exception) {
            runCatching { mu?.close() }
            runCatching { nfcA?.close() }
            return RawResult(null, "", "原始读取失败: ${e.message}")
        }

        val body = parseNdefTlv(data)
        if (body == null) {
            val cut = data.take(64).toByteArray()
            return RawResult(null, cut.toHex(), "未找到 NDEF TLV（原始数据已输出）")
        }
        return runCatching {
            RawResult(NdefMessage(body), body.toHex(), "$readerName 解析成功")
        }.getOrElse {
            RawResult(null, body.toHex(), "NDEF 构造失败: ${it.message}")
        }
    }

    /** NDEF TLV（0x03）解析，跳过 0x00 填充，遇到 0xFE 终止 */
    private fun parseNdefTlv(data: List<Byte>): ByteArray? {
        var i = 0
        while (i < data.size) {
            val t = data[i].toInt() and 0xFF
            if (t == 0x00) {
                i++
                continue
            }
            if (t == 0xFE) return null
            if (i + 1 >= data.size) return null
            var len = data[i + 1].toInt() and 0xFF
            var header = 2
            if (len == 0xFF) {
                // NDEF 规范：03 FF <长度高字节> <长度低字节>，两字节长度，头共 4 字节。
                // 原来按三字节读、header 记成 5，长度会被放大 256 倍且起始偏移错一位，
                // 超过 255 字节的 NDEF（长 URL、多记录的卡）必然解析失败。
                if (i + 3 >= data.size) return null
                len = ((data[i + 2].toInt() and 0xFF) shl 8) or
                    (data[i + 3].toInt() and 0xFF)
                header = 4
            }
            if (t == 0x03) {
                val start = i + header
                val end = start + len
                if (end > data.size) return null
                return data.subList(start, end).toByteArray()
            }
            i += header + len
        }
        return null
    }

    private fun recordsToResult(records: Array<NdefRecord>): Pair<List<String>, List<String>> {
        val lines = ArrayList<String>()
        val urls = ArrayList<String>()
        records.forEachIndexed { index, record ->
            val head = "记录${index + 1} [${tnfName(record.tnf)}]"
            val uri = runCatching { record.toUri() }.getOrNull()
            when {
                uri != null -> {
                    lines += "$head $uri"
                    urls += uri.toString()
                }

                record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(byteArrayOf(0x54)) -> {
                    val text = decodeTextRecord(record.payload)
                    lines += "$head $text"
                    urls += UrlPicker.extract(text)
                }

                else -> {
                    val payload = String(record.payload, Charsets.UTF_8)
                    lines += "$head " + payload.ifBlank { record.payload.toHex() }
                    urls += UrlPicker.extract(payload)
                }
            }
        }
        return lines to urls
    }

    private fun decodeTextRecord(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        return runCatching {
            val status = payload[0].toInt() and 0xFF
            val langLen = status and 0x3F
            val utf16 = (status and 0x80) != 0
            val start = 1 + langLen
            if (start > payload.size) return@runCatching ""
            val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
            String(payload, start, payload.size - start, charset)
        }.getOrDefault("")
    }

    private fun tnfName(tnf: Short): String = when (tnf.toInt()) {
        0x00 -> "EMPTY"
        0x01 -> "WELL_KNOWN"
        0x02 -> "MIME"
        0x03 -> "ABS_URI"
        0x04 -> "EXT_URI"
        0x05 -> "UNKNOWN"
        0x06 -> "UNCHANGED"
        0x07 -> "RESERVED"
        else -> "TNF$tnf"
    }

    private companion object {
        const val TAG = "NfcReader"
        const val DEBOUNCE_MS = 3000L
        const val RAW_MAX_PAGE = 60
    }
}

internal fun ByteArray.toHex(): String {
    if (isEmpty()) return ""
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        if (v < 16) sb.append('0')
        sb.append(Integer.toHexString(v))
    }
    return sb.toString().uppercase()
}
