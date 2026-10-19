package com.example.parentphoto.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * アプリパスワードの暗号化(ds 8.2)。Android Keystore の AES-256 鍵で AES/GCM/NoPadding。
 * 保存形式は Base64(IV 12バイト + 暗号文 + タグ)。復号に失敗した場合は null(未設定扱い)。
 * 平文はログに出さない。
 */
class PasswordCrypto(private val alias: String = DEFAULT_ALIAS) {

    /** 平文を暗号化して保存形式の文字列にする。鍵がなければここで作る。 */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey()) // IVは鍵側がランダム生成する
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    /** 復号する。空・破損・鍵消失などあらゆる失敗は null。 */
    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= IV_SIZE) return null
            val key = loadKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_SIZE))
            String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** テスト用: 鍵を削除する(鍵消失の再現)。 */
    fun deleteKey() {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
    }

    private fun loadKey(): SecretKey? =
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey {
        loadKey()?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "parent_photo_smtp_pw"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
