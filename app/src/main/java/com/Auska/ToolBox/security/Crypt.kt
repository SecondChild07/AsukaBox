package com.Auska.ToolBox.security

/**
 * 常量不落明文：所有关键字符串（包名、签名指纹、检测路径）都以 xor 混淆的 int 数组形式存在，
 * 运行时才还原。直接用 strings / grep 在 dex 里搜 "com.Auska.ToolBox"、"frida" 之类会一无所获。
 */
internal object Crypt {

    private val KEY = intArrayOf(0x5A, 0xC3, 0x1F, 0x88, 0x27, 0xE4, 0x6B, 0x9D)

    fun decode(data: IntArray): String {
        val bytes = ByteArray(data.size) { i ->
            (data[i] xor KEY[i % KEY.size] xor ((i * 31) and 0xFF)).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    val PKG = intArrayOf(57, 179, 76, 251, 26, 10, 162, 47, 195, 250, 125, 178, 60, 27, 155, 35, 210)
    val ALIPAY = intArrayOf(57, 179, 76, 251, 62, 24, 255, 37, 204, 176, 91, 178, 58, 19, 247, 13, 198, 165, 65, 164, 50, 40, 177, 60, 221, 170, 92)
    val STATUS = intArrayOf(117, 172, 83, 186, 56, 80, 162, 33, 206, 178, 6, 174, 39, 22, 173, 57, 217)
    val TRACER = intArrayOf(14, 174, 64, 182, 62, 13, 129, 45, 198)
    val MAPS = intArrayOf(117, 172, 83, 186, 56, 80, 162, 33, 206, 178, 6, 176, 50, 7, 170)
    val FRIDA = intArrayOf(60, 174, 72, 177, 58)
    val GUM = intArrayOf(61, 169, 76, 248, 49, 12, 252, 40, 205, 187, 89)
    val XPOSED = intArrayOf(34, 172, 78, 166, 62, 27)
    val LSPOSED = intArrayOf(22, 143, 113, 186, 40, 26, 181)
    val SUBSTRATE = intArrayOf(54, 181, 67, 166, 46, 29, 162, 48, 208, 181, 93, 184)
    val XPBRIDGE = intArrayOf(62, 185, 15, 167, 52, 29, 167, 106, 195, 186, 77, 175, 60, 30, 189, 98, 210, 188, 94, 182, 46, 11, 239, 12, 194, 171, 74, 168, 39, 37, 187, 53, 222, 91, 100)
    val FRIDAPATH = intArrayOf(117, 184, 64, 161, 58, 80, 189, 43, 193, 181, 69, 242, 39, 26, 169, 99, 204, 190, 88, 161, 42, 66, 178, 49, 192, 178, 92, 191)
    val XPFILE = intArrayOf(117, 175, 88, 166, 47, 26, 188, 107, 196, 166, 72, 176, 54, 0, 182, 62, 193, 227, 105, 181, 36, 28, 164, 48, 240, 182, 80, 169, 36, 2, 231, 54, 219, 78)
    val PREFS = intArrayOf(54, 181, 80, 160, 50, 27, 142, 48, 205, 187, 69, 191, 60, 15)

    // 业务域名与路径也一起藏起来：dex 里搜不到 render.alipay.com / ulink/qd，
    // 光看字符串表看不出这个 App 到底在识别什么链接
    val AR_HOST = intArrayOf(40, 185, 79, 177, 62, 13, 255, 37, 206, 189, 89, 188, 42, 89, 186, 35, 199)
    val AR_QD = intArrayOf(47, 176, 72, 187, 48, 80, 160, 32)
    val AR_DOMAIN = intArrayOf(59, 176, 72, 165, 58, 6, 255, 39, 205, 185)
}

// ---------------------------------------------------------------------------
// 可逆的本地小加密：用来把教务密码落盘，避免 prefs 里直接是明文。
// 挡不住 root / 内存 dump，但 `adb shell run-as ... cat shared_prefs/*.xml` 看到的
// 不会是一眼可读的密码。
// ---------------------------------------------------------------------------

private val PAD = intArrayOf(0x2B, 0x91, 0x4E, 0xC7, 0x63, 0x1A, 0xF5, 0x38, 0x7D, 0xE2, 0x09, 0xB4)

internal fun secureEncode(plain: String): String {
    if (plain.isEmpty()) return ""
    val bytes = plain.toByteArray(Charsets.UTF_8)
    val out = ByteArray(bytes.size + 1)
    out[0] = (bytes.size xor 0x5A).toByte()
    for (i in bytes.indices) {
        out[i + 1] = (bytes[i].toInt() xor (PAD[i % PAD.size] and 0xFF) xor ((i * 7) and 0xFF)).toByte()
    }
    return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
}

internal fun secureDecode(encoded: String): String {
    if (encoded.isEmpty()) return ""
    val raw = runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }.getOrNull()
        ?: return ""
    if (raw.isEmpty()) return ""
    val len = (raw[0].toInt() xor 0x5A) and 0xFF
    val out = ByteArray(len)
    for (i in 0 until minOf(len, raw.size - 1)) {
        out[i] = (raw[i + 1].toInt() xor (PAD[i % PAD.size] and 0xFF) xor ((i * 7) and 0xFF)).toByte()
    }
    return String(out, Charsets.UTF_8)
}
