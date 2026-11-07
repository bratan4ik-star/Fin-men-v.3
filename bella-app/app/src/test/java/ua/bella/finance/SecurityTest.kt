package ua.bella.finance

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** KV у пам'яті; failWrites — імітує невдалий commit(). */
class MemKV(val m: MutableMap<String, String> = HashMap(), var failWrites: Boolean = false) : KV {
    override fun get(key: String): String? = m[key]
    override fun write(put: Map<String, String>, remove: List<String>): Boolean {
        if (failWrites) return false
        remove.forEach { m.remove(it) }; m.putAll(put); return true
    }
}

class SecurityTest {
    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun valid(t: String) = try { json.decodeFromString<Persisted>(t); true } catch (_: Exception) { false }

    // ---------- PIN ----------
    @Test fun pinHashNewFormat() {
        val h = hashPin("1234")
        val p = h.split('$')
        assertEquals(4, p.size); assertEquals("pbkdf2", p[0]); assertEquals("120000", p[1])
        assertEquals(16, Crypto.unb64(p[2]).size); assertEquals(32, Crypto.unb64(p[3]).size)
        assertEquals(PinCheck(true, false), verifyPin("1234", h))
        assertNotEquals("та сама сіль не повторюється", h, hashPin("1234"))
    }

    @Test fun wrongPinIsRejected() {
        val h = hashPin("1234", iter = 1000)
        assertFalse(verifyPin("1235", h).ok); assertFalse(verifyPin("", h).ok)
        assertFalse(verifyPin("1234", "").ok); assertFalse(verifyPin("1234", "pbkdf2\$x\$y").ok); assertFalse(verifyPin("1234", "pbkdf2$1000$!!$!!").ok)
    }

    @Test fun legacyShaHashIsAcceptedAndMarkedForUpgrade() {
        val old = legacyPinHash("4321")
        assertEquals(64, old.length)
        assertEquals(PinCheck(true, true), verifyPin("4321", old))
        assertEquals(PinCheck(false, false), verifyPin("0000", old))
        // після міграції — новий формат, старий хеш більше не потрібен
        val upgraded = hashPin("4321")
        assertTrue(upgraded.startsWith("pbkdf2$")); assertEquals(PinCheck(true, false), verifyPin("4321", upgraded))
    }

    // ---------- блокування ----------
    @Test fun lockAfterFiveFailsThenDoublingUpToFifteenMinutes() {
        var s = PinLock(); val t0 = 1_000_000L
        repeat(4) { s = pinFailed(s, t0); assertEquals(0L, pinLockLeft(s, t0)) }
        assertEquals(4, s.fails)
        s = pinFailed(s, t0); assertEquals(30_000L, pinLockLeft(s, t0))           // 5-та помилка — 30 с
        val t1 = t0 + 30_000; assertEquals(0L, pinLockLeft(s, t1))
        s = pinFailed(s, t1); assertEquals(60_000L, pinLockLeft(s, t1))           // далі — удвічі довше
        val t2 = t1 + 60_000; s = pinFailed(s, t2); assertEquals(120_000L, pinLockLeft(s, t2))
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L), (0..6).map { pinLockDelay(it) })
        assertEquals(PIN_LOCK_MAX_MS, pinLockDelay(40))
    }

    @Test fun lockSurvivesRestartAndClockRollback() {
        val kv = MemKV(); var s = PinLock()
        repeat(5) { s = pinFailed(s, 0L) }
        PinLockStore.save(kv, s)
        val loaded = PinLockStore.load(kv)                                        // «перезапуск»
        assertEquals(s, loaded); assertEquals(30_000L, pinLockLeft(loaded, 0L))
        // годинник переведено назад на добу — чекати не довше максимуму
        assertEquals(PIN_LOCK_MAX_MS, pinLockLeft(loaded.copy(until = 10L * 24 * 3600_000), 0L))
        assertEquals("Забагато спроб. Зачекайте 30 с", pinLockText(30_000)); assertEquals("Забагато спроб. Зачекайте 2 хв", pinLockText(120_000))
        PinLockStore.save(kv, PinLock()); assertEquals(PinLock(), PinLockStore.load(kv))
    }

    // ---------- копія з паролем ----------
    private fun sample() = Persisted(data = AppData(txs = listOf(Tx("Секретна покупка", "Інше", "expense", 123.0, "2026-11-01")), demo = false),
        sec = Security(hash = hashPin("1111", iter = 1000)))

    @Test fun encryptedBackupRoundTrip() {
        val text = makeBackup(sample(), "2026-11-07T10:00:00", "пароль-123")
        assertFalse("у файлі немає відкритих даних", text.contains("Секретна покупка"))
        val f = readBackupFile(text) as BackupFile.Encrypted
        assertEquals("2026-11-07T10:00:00", f.env.createdAt); assertEquals(120_000, f.env.iter)
        assertEquals(16, Crypto.unb64(f.env.salt).size); assertEquals(12, Crypto.unb64(f.env.iv).size)
        val b = decryptBackup(f.env, "пароль-123")!!
        assertEquals("Секретна покупка", b.state.data.txs.single().desc)
        assertEquals("", b.state.sec.hash)                                         // PIN у копію не потрапляє
    }

    @Test fun wrongPasswordOrTamperedBackupIsRejected() {
        val env = encryptBackupText(makeBackup(sample(), "x"), "правильний", "x", iter = 1000)
        assertNull(decryptBackup(env, "неправильний"))
        val d = Crypto.unb64(env.data).also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertNull(decryptBackup(env.copy(data = Crypto.b64(d)), "правильний"))
        assertTrue(decryptBackup(env, "правильний") != null)
    }

    @Test fun oldPlainBackupsStillOpen() {
        val plain = makeBackup(sample(), "2026-01-01T00:00:00")
        assertTrue(readBackupFile(plain) is BackupFile.Plain)
        assertEquals("Секретна покупка", (readBackupFile(plain) as BackupFile.Plain).backup.state.data.txs.single().desc)
        assertEquals(BackupFile.Invalid, readBackupFile("""{"app":"other"}""")); assertEquals(BackupFile.Invalid, readBackupFile("не json"))
        assertEquals(BackupFile.Invalid, readBackupFile("""{"app":"bella-finance","encrypted":true,"salt":"","iv":"","data":""}"""))
    }

    // ---------- шифрування стану й міграція ----------
    @Test fun plainStateIsMigratedAndRemovedOnlyAfterVerifiedWrite() {
        val st = json.encodeToString(sample())
        val kv = MemKV(mutableMapOf(StateStore.PLAIN to st))
        val cipher = aesKey().let { k -> AesGcmCipher({ k }) }
        val r = StateStore.load(kv, cipher, ::valid)
        assertEquals(StateLoad.Ok(st, migrated = true), r)
        assertNull("відкритий стан видалено", kv.get(StateStore.PLAIN))
        val enc = kv.get(StateStore.ENC)!!
        assertTrue(enc.contains(':')); assertFalse(enc.contains("Секретна"))
        assertEquals(st, cipher.decrypt(enc))
        assertEquals(StateLoad.Ok(st), StateStore.load(kv, cipher, ::valid))      // повторний запуск — уже з stateEnc
    }

    @Test fun failedMigrationKeepsPlainState() {
        val st = json.encodeToString(sample())
        val broken = object : StringCipher { override fun encrypt(plain: String) = throw IllegalStateException("keystore"); override fun decrypt(packed: String) = throw IllegalStateException() }
        val kv = MemKV(mutableMapOf(StateStore.PLAIN to st))
        assertEquals(StateLoad.Ok(st, migrated = false), StateStore.load(kv, broken, ::valid))
        assertEquals(st, kv.get(StateStore.PLAIN)); assertNull(kv.get(StateStore.ENC))
        // запис зашифрованого не вдався — теж лишаємо відкритий
        val kv2 = MemKV(mutableMapOf(StateStore.PLAIN to st), failWrites = true)
        assertEquals(StateLoad.Ok(st, migrated = false), StateStore.load(kv2, AesGcmCipher({ aesKey() }), ::valid))
        assertEquals(st, kv2.get(StateStore.PLAIN))
        // шифрувальник, що повертає не те при читанні — запис відкочується, відкритий лишається
        val liar = object : StringCipher { override fun encrypt(plain: String) = "iv:ct"; override fun decrypt(packed: String) = "інше" }
        val kv3 = MemKV(mutableMapOf(StateStore.PLAIN to st))
        assertEquals(StateLoad.Ok(st, migrated = false), StateStore.load(kv3, liar, ::valid))
        assertEquals(st, kv3.get(StateStore.PLAIN)); assertNull(kv3.get(StateStore.ENC))
        // фонові задачі лише читають, без міграції
        val kv4 = MemKV(mutableMapOf(StateStore.PLAIN to st))
        assertEquals(StateLoad.Ok(st), StateStore.load(kv4, AesGcmCipher({ aesKey() }), ::valid, migrate = false)); assertNull(kv4.get(StateStore.ENC))
    }

    @Test fun lostKeyGivesFailedNotEmptyState() {
        val st = json.encodeToString(sample())
        val kv = MemKV(); val k1 = aesKey()
        assertTrue(StateStore.save(kv, AesGcmCipher({ k1 }), st))
        // ключ інший (Keystore скинуто) — Failed, а не порожній стан
        assertTrue(StateStore.load(kv, AesGcmCipher({ aesKey() }), ::valid) is StateLoad.Failed)
        // ключа немає зовсім
        assertTrue(StateStore.load(kv, AesGcmCipher({ aesKey() }, { null }), ::valid) is StateLoad.Failed)
        assertEquals(StateLoad.Empty, StateStore.load(MemKV(), AesGcmCipher({ k1 }), ::valid))
        // «почати з нуля»: нерозшифрований запис відкладається, не видаляється
        val enc = kv.get(StateStore.ENC)
        assertTrue(StateStore.setAsideBroken(kv)); assertNull(kv.get(StateStore.ENC)); assertEquals(enc, kv.get(StateStore.BROKEN))
    }

    @Test fun saveNeverWritesPlaintext() {
        val st = json.encodeToString(sample()); val k = aesKey()
        val kv = MemKV(mutableMapOf(StateStore.PLAIN to "старий"))
        assertTrue(StateStore.save(kv, AesGcmCipher({ k }), st))
        assertNull(kv.get(StateStore.PLAIN)); assertEquals(st, AesGcmCipher({ k }).decrypt(kv.get(StateStore.ENC)!!))
        val broken = object : StringCipher { override fun encrypt(plain: String) = throw IllegalStateException(); override fun decrypt(packed: String) = "" }
        val before = kv.m.toMap()
        assertFalse(StateStore.save(kv, broken, "новий")); assertEquals(before, kv.m)  // попередній запис не зачеплено
    }
}
