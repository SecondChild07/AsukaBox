package com.AusKa.ToolBox.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 本地敏感数据的加密盒子。
 *
 * 主钥匙放在 Android Keystore（AES-256/GCM，密钥在 TEE 里，导不出来）。
 *
 * **为什么还要一把「兜底钥匙」**：Keystore 里的密钥并不永恒 —— 刷机、换锁屏凭据、
 * 部分 ROM 的 OTA/清理策略都可能让某个 App 的密钥失效。密钥一旦没了，历史密文就再也
 * 解不开，而旧实现把「解不开」当成「没有数据」，用户看到的就是「重启一次，课表和签到
 * 链接全没了」，且无从恢复。所以需要长期保留的数据会被写成**双副本**：
 *
 * ```
 * ks2:<keystore 副本的 base64>.<app 内 AES 副本的 base64>
 * ```
 *
 * 读取时先试 Keystore，再试兜底副本；两条都解不开才判定为「不可读」。
 *
 * 兜底副本的钥匙是一串随机字节，存在本机 prefs 里（`asuka_vault`）。它挡不住拿到
 * prefs 目录的人，只挡「随手一读」—— 所以只有 [seal] 的 `recoverable = true` 才用它：
 * 课表、签到链接这类「丢了就没法用」的东西。密码、AI Key、会话 Cookie 仍然只走
 * Keystore（[seal] 默认），密钥失效时宁可让用户重新填一次，也不降级成一个能随手解开的盒子。
 *
 * 兼容的历史格式（[open] 都能读）：
 *  1. `ks2:` / `ks1:` —— Keystore 副本（+ 兜底副本）
 *  2. `fb1:` —— 只有兜底副本（写的时候 Keystore 正好不可用）
 *  3. `old:base64(...)` —— 更早的退化格式（[secureEncode]）
 *  4. 无前缀 —— 最早期直接存的 [secureEncode] 结果
 */
internal object SecretBox {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "asuka_box_vault"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32

    /** 双副本（Keystore + 兜底） */
    private const val DUAL = "ks2:"
    /** 只有 Keystore 副本，2.1.2 及更早写的都是这个 */
    private const val KS_ONLY = "ks1:"
    /** 只有兜底副本 */
    private const val FB_ONLY = "fb1:"
    private const val LEGACY = "old:"

    /** 两段副本之间的分隔符；base64 的字符集里没有 '.'，可以直接当分隔符 */
    private const val SEP = '.'

    private const val FB_PREFS = "asuka_vault"
    private const val FB_SECRET = "fb_secret"
    private const val FB_KEY_FILE = "vault.key"

    @Volatile
    private var cached: SecretKey? = null

    @Volatile
    private var appContext: Context? = null

    /** 最近一次「有密文但解不开」的时间戳，给设置页的自检用 */
    @Volatile
    var lastFailureAt: Long = 0L
        private set

    @Volatile
    var lastFailureKey: String = ""
        private set

    /** 进程级初始化（BoxApp 里调）。没初始化也能用，只是没有兜底副本。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        // 顺手把兜底钥匙建出来，免得第一次真正要用的时候赶上 IO 抖动
        runCatching { fbKey() }
    }

    // ------------------------------------------------------------------ Keystore 副本

    private fun key(): SecretKey? {
        cached?.let { return it }
        return runCatching {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val k = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build(),
                    )
                }
                .generateKey()
            cached = k
            k
        }.getOrNull()
    }

    /** Keystore 这把钥匙现在还能不能用（自检用；不能 = 老密文已经解不开了） */
    fun keystoreReady(): Boolean = key() != null

    // ------------------------------------------------------------------ 兜底副本

    /**
     * 兜底钥匙。第一次用到时生成 32 字节随机数。
     *
     * **刻意存在 files/vault.key 而不是 SharedPreferences 里**：那些 mirror 镜像文件
     * 存在的意义就是「prefs 被清掉/写坏时还能恢复」，钥匙要是也躺在 prefs 里，这条退路就等于没有。
     * 两个文件都在应用私有目录，外部访问的门槛和原来一样。
     *
     * 没有 Context（单测 / 页面还没初始化）时返回 null，调用方退回「只写 Keystore 副本」。
     */
    private fun fbKey(): SecretKey? {
        val ctx = appContext ?: return null
        return runCatching {
            val file = java.io.File(ctx.filesDir, FB_KEY_FILE)
            val raw = when {
                file.isFile -> Base64.decode(file.readText().trim(), Base64.NO_WRAP)
                // 早期版本把钥匙写在 prefs 里，读到了就迁到文件
                else -> {
                    val prefs = ctx.getSharedPreferences(FB_PREFS, Context.MODE_PRIVATE)
                    val legacy = prefs.getString(FB_SECRET, null)
                    if (legacy == null) ByteArray(0) else Base64.decode(legacy, Base64.NO_WRAP)
                }
            }
            if (raw.size >= KEY_BYTES) {
                if (!file.isFile) runCatching { file.writeText(Base64.encodeToString(raw, Base64.NO_WRAP)) }
                SecretKeySpec(raw, "AES")
            } else {
                val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
                file.writeText(Base64.encodeToString(fresh, Base64.NO_WRAP))
                // 顺手把可能存在的旧副本清掉，免得两个钥匙各存一份、以后改哪个都说不清
                runCatching {
                    ctx.getSharedPreferences(FB_PREFS, Context.MODE_PRIVATE).edit().remove(FB_SECRET).apply()
                }
                SecretKeySpec(fresh, "AES")
            }
        }.getOrNull()
    }

    /** 兜底副本是否可用（自检用） */
    fun fallbackReady(): Boolean = fbKey() != null

    // ------------------------------------------------------------------ 加解密

    private fun enc(k: SecretKey, plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, k)
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }.getOrNull()

    private fun dec(k: SecretKey, blob: String): String? {
        if (blob.isEmpty()) return null
        return runCatching {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return null
            val iv = raw.copyOfRange(0, IV_BYTES)
            val body = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * 明文 → 密文。空串原样返回（省得每次判空）。
     *
     * @param recoverable 长期要留住的数据传 true：会同时写一份兜底副本，
     *                    Keystore 被系统重置后还能读回来。密码/Key/会话保持 false。
     */
    fun seal(plain: String, recoverable: Boolean = false): String {
        if (plain.isEmpty()) return ""
        val ks = key()?.let { enc(it, plain) }
        if (!recoverable) {
            // 强保护：只写 Keystore 副本
            if (ks != null) return KS_ONLY + ks
            // Keystore 不可用时不能什么都存不下 —— 退回兜底副本，再不行才 xor
            val fb = fbKey()?.let { enc(it, plain) }
            return if (fb != null) FB_ONLY + fb else LEGACY + secureEncode(plain)
        }
        val fb = fbKey()?.let { enc(it, plain) }
        return when {
            ks != null && fb != null -> DUAL + ks + SEP + fb
            ks != null -> KS_ONLY + ks
            fb != null -> FB_ONLY + fb
            else -> LEGACY + secureEncode(plain)
        }
    }

    /** 密文 → 明文；解不开返回空串（老调用方的约定：按「没有」处理）。 */
    fun open(sealed: String): String = openOrNull(sealed).orEmpty()

    /**
     * 密文 → 明文；**解不开返回 null**。
     *
     * 和 [open] 的区别在于能区分「本来就没存」和「存了但读不出来」——
     * 后者绝不能当成空数据覆盖掉，得让界面把话说清楚。
     */
    fun openOrNull(sealed: String): String? {
        if (sealed.isEmpty()) return null
        val (value, ok) = decode(sealed)
        if (ok) return value
        lastFailureAt = System.currentTimeMillis()
        lastFailureKey = sealed.take(4)
        return null
    }

    private fun decode(sealed: String): Pair<String, Boolean> {
        when {
            sealed.startsWith(DUAL) -> {
                val body = sealed.removePrefix(DUAL)
                val cut = body.indexOf(SEP)
                if (cut <= 0) return "" to false
                val ksPart = body.substring(0, cut)
                val fbPart = body.substring(cut + 1)
                key()?.let { k -> dec(k, ksPart)?.let { return it to true } }
                fbKey()?.let { k -> dec(k, fbPart)?.let { return it to true } }
                return "" to false
            }

            sealed.startsWith(KS_ONLY) -> {
                val blob = sealed.removePrefix(KS_ONLY)
                val k = key() ?: return "" to false
                val v = dec(k, blob) ?: return "" to false
                return v to true
            }

            sealed.startsWith(FB_ONLY) -> {
                val blob = sealed.removePrefix(FB_ONLY)
                val k = fbKey() ?: return "" to false
                val v = dec(k, blob) ?: return "" to false
                return v to true
            }

            sealed.startsWith(LEGACY) -> return secureDecode(sealed.removePrefix(LEGACY)) to true

            // 没有前缀：最早期版本直接存的 xor 结果。xor 解不开会是乱码而不是空串，
            // 这里没法判断，仍按「读到了」返回（调用方的 accept() 谓词会兜住）
            else -> return secureDecode(sealed) to true
        }
    }

    /** 这个字段是不是「有密文」—— 自检用来区分「没配」和「配了但丢了」 */
    fun present(sealed: String): Boolean = sealed.isNotEmpty()

    /**
     * 兜底副本能不能自己单独解开这条密文（不碰 Keystore）。
     *
     * 课表/链接的本地镜像文件用它：Keystore 挂了的时候，镜像是唯一还能恢复的来源。
     */
    fun openFallbackOnly(sealed: String): String? {
        val body = when {
            sealed.startsWith(DUAL) -> {
                val cut = sealed.indexOf(SEP)
                if (cut <= 0) return null
                sealed.substring(cut + 1)
            }

            sealed.startsWith(FB_ONLY) -> sealed.removePrefix(FB_ONLY)
            else -> return null
        }
        val k = fbKey() ?: return null
        return dec(k, body)
    }

    /** 把密文降级成「只有兜底副本」的形态，写镜像文件时用（镜像不需要 Keystore 那一段） */
    fun fallbackPart(sealed: String): String? {
        return when {
            sealed.startsWith(DUAL) -> {
                val cut = sealed.indexOf(SEP)
                if (cut <= 0) null else FB_ONLY + sealed.substring(cut + 1)
            }

            sealed.startsWith(FB_ONLY) -> sealed
            else -> null
        }
    }

    /** 给「数据自检」用的一句话摘要 */
    fun statusLine(): String {
        val ks = if (keystoreReady()) "可用" else "不可用"
        val fb = if (fallbackReady()) "已就绪" else "未初始化"
        return "Keystore $ks · 兜底钥匙 $fb"
    }

    /** 仅用于自检展示：把一段密文解出来的长度（0 = 解不开） */
    fun probe(sealed: String): Int = openOrNull(sealed)?.length ?: -1

    /** 内容指纹，自检里判断「这条到底是不是我存的那份」又不暴露原文 */
    fun fingerprint(plain: String): String {
        if (plain.isEmpty()) return ""
        val md = MessageDigest.getInstance("SHA-256").digest(plain.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(8)
        for (i in 0 until 4) sb.append("%02x".format(md[i]))
        return sb.toString()
    }
}
