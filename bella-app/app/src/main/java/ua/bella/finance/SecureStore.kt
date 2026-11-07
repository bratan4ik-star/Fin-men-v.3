package ua.bella.finance

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Ключі AES-256 в AndroidKeyStore: не покидають захищене сховище телефону й не потрапляють у резервні копії. */
object KeystoreKeys {
    const val STATE = "bella-state"

    private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Наявний ключ або null (для розшифрування: новий ключ тут не створюється, бо він однаково не підійде). */
    fun existing(alias: String): SecretKey? = (ks().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    /** Наявний ключ або новий. */
    fun getOrCreate(alias: String): SecretKey = existing(alias) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        generateKey()
    }

    /** Видаляє ключ (лише коли користувач свідомо починає з порожніми даними після збою ключа). */
    fun delete(alias: String) { try { ks().deleteEntry(alias) } catch (_: Exception) {} }

    /** Шифрувальник стану: AES-256-GCM, ключ «bella-state». */
    val stateCipher: StringCipher = AesGcmCipher({ getOrCreate(STATE) }, { existing(STATE) })
}

/** KV поверх SharedPreferences: кожен запис — один commit(). */
class PrefsKV(private val p: SharedPreferences) : KV {
    override fun get(key: String): String? = try { p.getString(key, null) } catch (_: ClassCastException) { p.all[key]?.toString() }
    override fun getLong(key: String): Long = try { p.getLong(key, 0L) } catch (_: ClassCastException) { get(key)?.toLongOrNull() ?: 0L }
    override fun write(put: Map<String, String>, remove: List<String>): Boolean =
        p.edit().apply { remove.forEach { remove(it) }; put.forEach { (k, v) -> putString(k, v) } }.commit()

    companion object { fun of(ctx: Context) = PrefsKV(ctx.getSharedPreferences("bella", Context.MODE_PRIVATE)) }
}

/** Пароль автокопії: зберігається зашифрованим ключем «bella-state», щоб фонова автокопія могла шифрувати файл. */
object BackupPassword {
    private const val KEY = "backupPw"
    fun isSet(ctx: Context): Boolean = PrefsKV.of(ctx).get(KEY) != null
    fun get(ctx: Context): String? = PrefsKV.of(ctx).get(KEY)?.let { try { KeystoreKeys.stateCipher.decrypt(it) } catch (_: Exception) { null } }
    fun set(ctx: Context, pw: String): Boolean = try { PrefsKV.of(ctx).write(mapOf(KEY to KeystoreKeys.stateCipher.encrypt(pw))) } catch (_: Exception) { false }
    fun clear(ctx: Context) { PrefsKV.of(ctx).write(remove = listOf(KEY)) }
}
