package ua.bella.finance

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/*
 * Фонові задачі раз на добу (WorkManager, близько 9:00): тижнева автокопія в обрану папку і нагадування про виплати ОВДП.
 * Ті самі перевірки запускаються й при відкритті застосунку, тож пропущена задача наздоганяється.
 * Налаштування — у SharedPreferences «bella» поруч зі станом: autoDir, autoAt, remindOvdp, notified, remindMoney, notifiedMoney.
 */
object Jobs {
    private const val PREFS = "bella"
    private const val CHANNEL = "ovdp"
    private const val CHANNEL_MONEY = "money"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun schedule(ctx: Context) {
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(9, 0); if (!at.isAfter(now)) at = at.plusDays(1)
        val initialDelayMinutes = Duration.between(now, at).toMinutes().coerceAtLeast(1)
        val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("bella-daily", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Запуск негайної фонової перевірки через WorkManager (OneTimeWorkRequest). */
    fun runImmediateCheck(ctx: Context) {
        val req = androidx.work.OneTimeWorkRequestBuilder<DailyWorker>().build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** Збережений стан (розшифрований). Лише читання: міграцію відкритого «state» робить сам застосунок. Помилка — null, і нічого не пишеться. */
    private fun state(ctx: Context): Persisted? = try {
        val r = StateStore.load(PrefsKV.of(ctx), KeystoreKeys.stateCipher, { t -> try { json.decodeFromString<Persisted>(t); true } catch (_: Exception) { false } }, migrate = false)
        (r as? StateLoad.Ok)?.let { json.decodeFromString<Persisted>(it.json) }
    } catch (_: Exception) { null }

    // ---------- автокопія ----------
    fun autoDir(ctx: Context): Uri? = prefs(ctx).getString("autoDir", null)?.let(Uri::parse)
    fun autoAt(ctx: Context): Long = prefs(ctx).getLong("autoAt", 0L)
    fun setAutoDir(ctx: Context, uri: Uri?) {
        val old = autoDir(ctx)
        if (old != null && old != uri) try { ctx.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        if (uri != null) ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs(ctx).edit().apply { if (uri == null) remove("autoDir") else putString("autoDir", uri.toString()) }.putLong("autoAt", 0L).apply()
    }

    /** Робить автокопію, якщо минув тиждень (або [force]). Повертає текст результату або null, якщо копія не потрібна. */
    @Synchronized
    fun runAutoBackup(ctx: Context, force: Boolean = false): String? {
        val dir = autoDir(ctx) ?: return if (force) "Спершу виберіть папку для автокопій" else null
        if (!force && !autoBackupDue(autoAt(ctx), System.currentTimeMillis())) return null
        val st = state(ctx) ?: return "Немає даних для копії"
        // пароль копії (якщо задано) зберігається зашифрованим; не прочитався — копію не робимо, щоб не записати відкритий файл
        val pw = if (BackupPassword.isSet(ctx)) BackupPassword.get(ctx) ?: return "Не вдалося прочитати пароль автокопії. Задайте його ще раз у Налаштуваннях." else null
        return try {
            val r = ctx.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(dir, DocumentsContract.getTreeDocumentId(dir))
            val name = autoBackupName(TODAY)
            val children = listChildren(ctx, dir)
            children[name]?.let { DocumentsContract.deleteDocument(r, it) }
            val doc = DocumentsContract.createDocument(r, parent, "application/json", name) ?: return "Не вдалося створити файл автокопії"
            r.openOutputStream(doc, "wt")?.use { it.write(makeBackup(st, LocalDateTime.now().withNano(0).toString(), pw).toByteArray(Charsets.UTF_8)) } ?: return "Не вдалося записати автокопію"
            val all = listChildren(ctx, dir)
            autoBackupsToDelete(all.keys.toList()).forEach { n -> all[n]?.let { try { DocumentsContract.deleteDocument(r, it) } catch (_: Exception) {} } }
            prefs(ctx).edit().putLong("autoAt", System.currentTimeMillis()).apply()
            "Автокопію збережено: $name"
        } catch (_: Exception) { "Немає доступу до папки автокопій. Виберіть її ще раз." }
    }

    /** Текст найновішої автокопії в папці (для екрана відновлення) або null. */
    fun latestAutoBackupText(ctx: Context): String? = try {
        val dir = autoDir(ctx) ?: return null
        val all = listChildren(ctx, dir)
        val name = all.keys.filter { it.startsWith(AUTO_PREFIX) && it.endsWith(".json") }.maxOrNull() ?: return null
        ctx.contentResolver.openInputStream(all.getValue(name))?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) { null }

    private fun listChildren(ctx: Context, tree: Uri): Map<String, Uri> {
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = HashMap<String, Uri>()
        ctx.contentResolver.query(q, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) out[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return out
    }

    // ---------- нагадування ----------
    fun remindOn(ctx: Context) = prefs(ctx).getBoolean("remindOvdp", true)
    fun setRemind(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("remindOvdp", on).apply() }
    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun moneyOn(ctx: Context) = prefs(ctx).getBoolean("remindMoney", true)
    fun setMoney(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("remindMoney", on).apply() }

    /** Ліміти (80% / 100%) і терміни боргів; викликається щодня, при відкритті й після кожної зміни даних. */
    @Synchronized
    fun runMoneyAlerts(ctx: Context) {
        if (!moneyOn(ctx) || !canNotify(ctx)) return
        val st = state(ctx) ?: return
        val today = LocalDate.parse(TODAY)
        val p = prefs(ctx)
        val notified = pruneNotified(p.getStringSet("notifiedMoney", emptySet()) ?: emptySet(), today)
        val due = limitAlerts(st.data.txs, st.data.limits, today, notified) + debtAlerts(st.data.debts, today, notified)
        if (due.isEmpty()) { p.edit().putStringSet("notifiedMoney", notified).apply(); return }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL_MONEY, "Ліміти й борги", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Витрати досягли 80% або 100% ліміту; термін повернення боргу" })
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        due.forEach { a ->
            val n = NotificationCompat.Builder(ctx, CHANNEL_MONEY).setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(a.title).setContentText(a.text).setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
                .setContentIntent(open).setAutoCancel(true).build()
            try { NotificationManagerCompat.from(ctx).notify(a.key.replace("|80|", "|100|").hashCode(), n) } catch (_: SecurityException) {}
        }
        p.edit().putStringSet("notifiedMoney", notified + due.flatMap { alertKeys(it) }).apply()
    }

    fun runReminders(ctx: Context) {
        try { runMoneyAlerts(ctx) } catch (_: Exception) {}
        if (!remindOn(ctx) || !canNotify(ctx)) return
        val st = state(ctx) ?: return
        val today = LocalDate.parse(TODAY)
        val p = prefs(ctx)
        val notified = pruneNotified(p.getStringSet("notified", emptySet()) ?: emptySet(), today)
        val due = remindersDue(ovdpEvents(st.data.pos, today, today.plusDays(3), st.nbu.byIsin()), today, notified)
        if (due.isEmpty()) { p.edit().putStringSet("notified", notified).apply(); return }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Виплати ОВДП", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Купони й погашення ОВДП: за 3 дні й у день виплати"
                    enableVibration(true)
                }
            )
        }
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("screen", "calc")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        due.forEach { r ->
            val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(reminderTitle(r))
                .setContentText(reminderText(r, RATES))
                .setStyle(NotificationCompat.BigTextStyle().bigText(reminderText(r, RATES)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(r.key.hashCode(), n) } catch (_: SecurityException) {}
        }
        p.edit().putStringSet("notified", notified + due.map { it.key }).apply()
    }

    /** Надсилає тестове сповіщення про ОВДП для миттєвої перевірки на пристрої користувача. */
    fun sendTestOvdpNotification(ctx: Context): String {
        if (!canNotify(ctx)) return "Сповіщення вимкнені у налаштуваннях Android. Дозвольте їх, будь ласка."
        val st = state(ctx)
        val today = LocalDate.parse(TODAY)
        val nextEvents = if (st != null) ovdpEvents(st.data.pos, today, today.plusDays(365), st.nbu.byIsin()) else emptyList()
        val testEvent = nextEvents.firstOrNull() ?: OvdpEvent(
            date = today.plusDays(3),
            name = "ОВДП UA4000227045 (тест)",
            coupon = 85.50,
            principal = 1000.0,
            cur = "UAH"
        )
        val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, testEvent.date).coerceAtLeast(0)
        val testReminder = Reminder("test|${testEvent.name}|${testEvent.date}", testEvent, daysLeft == 0L, daysLeft)

        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Виплати ОВДП", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Купони й погашення ОВДП: за 3 дні й у день виплати"
                    enableVibration(true)
                }
            )
        }

        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("screen", "calc")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("🔔 " + reminderTitle(testReminder))
            .setContentText(reminderText(testReminder, RATES))
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminderText(testReminder, RATES)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(ctx).notify(9999, n)
            "Тестове сповіщення про виплату надіслано!"
        } catch (_: SecurityException) {
            "Немає дозволу на надсилання сповіщень"
        }
    }
}

class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try { Jobs.runAutoBackup(applicationContext) } catch (_: Exception) {}
        try { Jobs.runReminders(applicationContext) } catch (_: Exception) {}
        return Result.success()
    }
}
