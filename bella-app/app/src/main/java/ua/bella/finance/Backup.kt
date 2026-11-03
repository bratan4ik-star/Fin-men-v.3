package ua.bella.finance

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * Резервна копія — один JSON-файл з усіма даними (операції, рахунки, борги, інвестиції, цілі, ліміти,
 * налаштування, історія капіталу). PIN у копію не потрапляє: після відновлення лишається PIN цього телефону.
 * Файл не шифрується.
 */
@Serializable
data class Backup(val app: String = BACKUP_APP, val format: Int = 1, val createdAt: String = "", val state: Persisted = Persisted())

const val BACKUP_APP = "bella-finance"

private val backupJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

fun makeBackup(p: Persisted, createdAt: String): String = backupJson.encodeToString(Backup(createdAt = createdAt, state = p.copy(sec = Security())))

/** Розбирає копію; null, якщо це не копія Bella Finance або файл пошкоджено. */
fun parseBackup(text: String): Backup? = try {
    backupJson.decodeFromString<Backup>(text).takeIf { it.app == BACKUP_APP }
} catch (_: Exception) { null }

fun backupFileName(date: String): String = "bella-backup-$date.json"
