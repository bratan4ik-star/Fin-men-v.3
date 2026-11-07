package ua.bella.finance

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Резервна копія — один JSON-файл з усіма даними (операції, рахунки, борги, інвестиції, цілі, ліміти,
 * налаштування, історія капіталу). PIN у копію не потрапляє: після відновлення лишається PIN цього телефону.
 *
 * Копію можна зашифрувати паролем (format 2): ключ AES-256 з пароля через PBKDF2WithHmacSHA256
 * (сіль 16 байт, 120 000 ітерацій), дані — AES-GCM; сіль, iv і кількість ітерацій записані у файлі.
 * Відкриті копії (format 1, у тому числі зроблені до 1.26.0) відновлюються як і раніше.
 */
@Serializable
data class Backup(val app: String = BACKUP_APP, val format: Int = 1, val createdAt: String = "", val state: Persisted = Persisted())

/** Зашифрована копія: дата відкрита (щоб показати перед введенням пароля), усе інше — у data. */
@Serializable
data class EncryptedBackup(
    val app: String = BACKUP_APP, val format: Int = 2, val encrypted: Boolean = true, val createdAt: String = "",
    val kdf: String = "PBKDF2WithHmacSHA256", val iter: Int = Crypto.ITER, val salt: String = "", val iv: String = "", val data: String = "",
)

const val BACKUP_APP = "bella-finance"

private val backupJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

/** Копія; з [password] — зашифрована. */
fun makeBackup(p: Persisted, createdAt: String, password: String? = null): String {
    val plain = backupJson.encodeToString(Backup(createdAt = createdAt, state = p.copy(sec = Security())))
    if (password.isNullOrEmpty()) return plain
    return backupJson.encodeToString(encryptBackupText(plain, password, createdAt))
}

fun encryptBackupText(plain: String, password: String, createdAt: String, iter: Int = Crypto.ITER): EncryptedBackup {
    val salt = Crypto.random(16)
    val (iv, ct) = Crypto.gcmEncrypt(Crypto.passwordKey(password, salt, iter), plain.toByteArray(Charsets.UTF_8))
    return EncryptedBackup(createdAt = createdAt, iter = iter, salt = Crypto.b64(salt), iv = Crypto.b64(iv), data = Crypto.b64(ct))
}

/** Що лежить у файлі копії. */
sealed class BackupFile {
    data class Plain(val backup: Backup) : BackupFile()
    data class Encrypted(val env: EncryptedBackup) : BackupFile()
    data object Invalid : BackupFile()
}

fun readBackupFile(text: String): BackupFile = try {
    val o = Json.parseToJsonElement(text).jsonObject
    when {
        o["app"]?.jsonPrimitive?.content != BACKUP_APP -> BackupFile.Invalid
        o["encrypted"]?.jsonPrimitive?.booleanOrNull == true -> backupJson.decodeFromString<EncryptedBackup>(text).let {
            if (it.salt.isEmpty() || it.iv.isEmpty() || it.data.isEmpty() || it.iter !in 1..10_000_000) BackupFile.Invalid else BackupFile.Encrypted(it)
        }
        else -> BackupFile.Plain(backupJson.decodeFromString<Backup>(text))
    }
} catch (_: Exception) { BackupFile.Invalid }

/** Розшифровує копію; null — невірний пароль або файл пошкоджено (GCM перевіряє цілісність). */
fun decryptBackup(env: EncryptedBackup, password: String): Backup? = try {
    val key = Crypto.passwordKey(password, Crypto.unb64(env.salt), env.iter)
    val plain = String(Crypto.gcmDecrypt(key, Crypto.unb64(env.iv), Crypto.unb64(env.data)), Charsets.UTF_8)
    (readBackupFile(plain) as? BackupFile.Plain)?.backup
} catch (_: Exception) { null }

/** Відкрита копія (стара функція): null, якщо це не відкрита копія Bella Finance або файл пошкоджено. */
fun parseBackup(text: String): Backup? = (readBackupFile(text) as? BackupFile.Plain)?.backup

fun backupFileName(date: String): String = "bella-backup-$date.json"
