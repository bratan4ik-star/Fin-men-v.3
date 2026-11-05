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
 * Налаштування — у SharedPreferences «bella» поруч зі станом: autoDir, autoAt, remindOvdp, notified.
 */
object Jobs {
    private const val PREFS = "bella"
    private const val CHANNEL = "ovdp"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun schedule(ctx: Context) {
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(9, 0); if (!at.isAfter(now)) at = at.plusDays(1)
        val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, at).toMinutes(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("bella-daily", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun state(ctx: Context): Persisted? = try { prefs(ctx).getString("state", null)?.let { json.decodeFromString<Persisted>(it) } } catch (_: Exception) { null }

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
        return try {
            val r = ctx.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(dir, DocumentsContract.getTreeDocumentId(dir))
            val name = autoBackupName(TODAY)
            val children = listChildren(ctx, dir)
            children[name]?.let { DocumentsContract.deleteDocument(r, it) }
            val doc = DocumentsContract.createDocument(r, parent, "application/json", name) ?: return "Не вдалося створити файл автокопії"
            r.openOutputStream(doc, "wt")?.use { it.write(makeBackup(st, LocalDateTime.now().withNano(0).toString()).toByteArray(Charsets.UTF_8)) } ?: return "Не вдалося записати автокопію"
            val all = listChildren(ctx, dir)
            autoBackupsToDelete(all.keys.toList()).forEach { n -> all[n]?.let { try { DocumentsContract.deleteDocument(r, it) } catch (_: Exception) {} } }
            prefs(ctx).edit().putLong("autoAt", System.currentTimeMillis()).apply()
            "Автокопію збережено: $name"
        } catch (_: Exception) { "Немає доступу до папки автокопій. Виберіть її ще раз." }
    }

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

    fun runReminders(ctx: Context) {
        if (!remindOn(ctx) || !canNotify(ctx)) return
        val st = state(ctx) ?: return
        val today = LocalDate.parse(TODAY)
        val p = prefs(ctx)
        val notified = pruneNotified(p.getStringSet("notified", emptySet()) ?: emptySet(), today)
        val due = remindersDue(ovdpEvents(st.data.pos, today, today.plusDays(3)), today, notified)
        if (due.isEmpty()) { p.edit().putStringSet("notified", notified).apply(); return }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Виплати ОВДП", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Купони й погашення ОВДП: за 3 дні й у день виплати" })
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        due.forEach { r ->
            val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(reminderTitle(r)).setContentText(reminderText(r)).setStyle(NotificationCompat.BigTextStyle().bigText(reminderText(r)))
                .setContentIntent(open).setAutoCancel(true).build()
            try { NotificationManagerCompat.from(ctx).notify(r.key.hashCode(), n) } catch (_: SecurityException) {}
        }
        p.edit().putStringSet("notified", notified + due.map { it.key }).apply()
    }
}

class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try { Jobs.runAutoBackup(applicationContext) } catch (_: Exception) {}
        try { Jobs.runReminders(applicationContext) } catch (_: Exception) {}
        return Result.success()
    }
}
