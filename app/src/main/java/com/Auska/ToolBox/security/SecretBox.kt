package com.Auska.ToolBox.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地敏感数据的加密盒子。
 *
 * 用 Android Keystore 里的一把 AES-256 密钥做 AES/GCM：密钥生成在 TEE/StrongBox 里，
 * 进程只能「用」它，导不出来 —— 所以 `adb shell cat shared_prefs` 下面那些 xml 拿到的只有密文，
 * 把文件拷到别的设备上也解不开。
 *
 * 为了让老版本存的数据还能读，[open] 兼容三种形态：
 *  1. `ks1:base64(iv + ciphertext)` —— 当前格式
 *  2. `old:base64(...)` —— Keystore 不可用时的退化格式（[secureEncode]）
 *  3. 无前缀 —— 更早版本直接存的 [secureEncode] 结果
 */
internal object SecretBox {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "asuka_box_vault"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PREFIX = "ks1:"
    private const val LEGACY = "old:"

    @Volatile
    private var cached: SecretKey? = null

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

    /** 明文 → 密文；空串原样返回（省得每次判空） */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val k = key() ?: return LEGACY + secureEncode(plain)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrElse { LEGACY + secureEncode(plain) }
    }

    /** 密文 → 明文；解不开就返回空串，调用方按「没有」处理 */
    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        if (sealed.startsWith(LEGACY)) return secureDecode(sealed.removePrefix(LEGACY))
        if (!sealed.startsWith(PREFIX)) return secureDecode(sealed)

        val k = key() ?: return ""
        return runCatching {
            val raw = Base64.decode(sealed.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return@runCatching ""
            val iv = raw.copyOfRange(0, IV_BYTES)
            val body = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
