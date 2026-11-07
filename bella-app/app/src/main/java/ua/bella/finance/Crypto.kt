package ua.bella.finance

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Криптографія застосунку без залежності від Android (тестується на JVM):
 * AES-256-GCM, PBKDF2WithHmacSHA256, формат «base64(iv):base64(шифротекст)».
 * Ключ з AndroidKeyStore підставляється ззовні (KeystoreKeys.kt).
 */
object Crypto {
    /** Ітерацій PBKDF2 для PIN і пароля копії. */
    const val ITER = 120_000
    private const val GCM_TAG_BITS = 128
    private val rnd = SecureRandom()
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun random(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }
    fun b64(b: ByteArray): String = b64e.encodeToString(b)
    fun unb64(s: String): ByteArray = b64d.decode(s)

    /** PBKDF2WithHmacSHA256: bits — довжина результату. */
    fun pbkdf2(secret: String, salt: ByteArray, iter: Int, bits: Int = 256): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iter, bits)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    /** Шифрує AES-GCM; IV генерує сам шифр (вимога AndroidKeyStore). Повертає iv і шифротекст з тегом. */
    fun gcmEncrypt(key: SecretKey, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv to c.doFinal(plain)
    }

    /** Розшифровує AES-GCM; кидає виняток, якщо ключ не той або дані змінено (перевірка тегу). */
    fun gcmDecrypt(key: SecretKey, iv: ByteArray, ct: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return c.doFinal(ct)
    }

    /** «base64(iv):base64(ct)». */
    fun pack(iv: ByteArray, ct: ByteArray): String = b64(iv) + ":" + b64(ct)
    fun unpack(s: String): Pair<ByteArray, ByteArray> {
        val i = s.indexOf(':'); require(i > 0) { "Невідомий формат зашифрованих даних" }
        return unb64(s.substring(0, i)) to unb64(s.substring(i + 1))
    }

    /** Ключ AES-256 з пароля (для копій). */
    fun passwordKey(password: String, salt: ByteArray, iter: Int): SecretKey = SecretKeySpec(pbkdf2(password, salt, iter, 256), "AES")
}

/** Шифрувальник рядків: так зберігається стан і пароль автокопії. Абстракція, щоб тестувати без Keystore. */
interface StringCipher {
    /** «base64(iv):base64(ct)». */
    fun encrypt(plain: String): String
    /** Кидає виняток, якщо ключа немає, він інший або дані пошкоджено. */
    fun decrypt(packed: String): String
}

/** AES-GCM з ключем, який дає [key] (з Keystore або, у тестах, звичайний). [keyForDecrypt] не створює новий ключ. */
class AesGcmCipher(private val key: () -> SecretKey, private val keyForDecrypt: () -> SecretKey? = { key() }) : StringCipher {
    override fun encrypt(plain: String): String = Crypto.gcmEncrypt(key(), plain.toByteArray(Charsets.UTF_8)).let { (iv, ct) -> Crypto.pack(iv, ct) }
    override fun decrypt(packed: String): String {
        val k = keyForDecrypt() ?: throw IllegalStateException("Ключ шифрування відсутній")
        val (iv, ct) = Crypto.unpack(packed)
        return String(Crypto.gcmDecrypt(k, iv, ct), Charsets.UTF_8)
    }
}
