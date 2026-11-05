package ir.mmdkavir.kavirdl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores secrets encrypted with an AES-GCM key that lives in the Android Keystore. */
object Secure {
    private const val ALIAS = "kavirdl_secret_key"
    private const val FILE = "kdl_secure"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(ctx: Context, name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(name, blob).apply()
    }

    fun get(ctx: Context, name: String): String {
        val blob = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(name, null) ?: return ""
        return try {
            val (iv, enc) = blob.split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    fun remove(ctx: Context, name: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(name).apply()
    }
}
