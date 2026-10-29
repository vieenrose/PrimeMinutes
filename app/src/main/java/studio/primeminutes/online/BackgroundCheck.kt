package studio.primeminutes.online

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.withTimeoutOrNull
import studio.primeminutes.R
import java.util.concurrent.TimeUnit

/**
 * M4 phase one (docs/design/podcast-series.md): a daily background check of the subscribed feeds that only
 * notifies. It reuses [SubscriptionActions.checkAll] (conditional GET, 301/308 rekey, paused skipped) and never
 * downloads audio. Off by default; scheduled only while the setting is on and an active subscription exists.
 */
object BackgroundCheck {
    const val WORK_NAME = "podcast-new-episodes"
    const val CHANNEL_ID = "new_episodes"
    const val NOTIFICATION_ID = 4401
    /** MainActivity extra: the channel (subscription id) to open; blank = the shows list. */
    const val EXTRA_OPEN_CHANNEL = "studio.primeminutes.OPEN_CHANNEL"
    const val INTERVAL_HOURS = 24L
    const val TIME_BOX_MS = 30_000L
    private const val PREFS = "primeminutes"
    private const val KEY_ENABLED = "bg_check_new_episodes"

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        sync(ctx, force = true)
    }

    /** The scheduling decision: the setting is on and at least one subscription is not paused. */
    fun shouldSchedule(enabled: Boolean, subs: List<Subscription>) = enabled && subs.any { !it.paused }

    @Volatile private var lastDecision: Boolean? = null

    /** Enqueues (KEEP) or cancels the periodic work to match [shouldSchedule]; cheap when nothing changed. */
    fun sync(ctx: Context, subs: List<Subscription> = Subscriptions.load(ctx), force: Boolean = false) {
        val want = shouldSchedule(enabled(ctx), subs)
        if (!force && lastDecision == want) return
        runCatching {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            if (want) wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
            else wm.cancelUniqueWork(WORK_NAME)
            lastDecision = want
        }
    }

    fun request() = PeriodicWorkRequestBuilder<NewEpisodesWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
        .build()

    /** A newly found episode: its channel and title. */
    data class Found(val subId: String, val channel: String, val episode: String)

    /**
     * Episodes this check discovered: new (unseen) after the check and unknown before it. A subscription that moved
     * (301/308 changes its id) is matched to its old self by addedAt + title. Paused subscriptions never count.
     */
    fun diff(before: List<Subscription>, after: List<Subscription>): List<Found> {
        val byId = before.associateBy { it.id }
        return after.filter { !it.paused }.flatMap { s ->
            val old = byId[s.id] ?: before.firstOrNull { it.addedAt == s.addedAt && it.title == s.title } ?: return@flatMap emptyList()
            val known = old.episodes.map { it.guid }.toHashSet().apply { addAll(old.seenGuids) }
            s.newEpisodes.filter { it.guid !in known }.map { Found(s.id, s.title, it.title) }
        }
    }

    fun canNotify(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** One notification for the whole run (InboxStyle: "Channel · Episode" lines). Never asks for permission. */
    fun notify(ctx: Context, found: List<Found>) {
        if (found.isEmpty() || !canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.bg_check_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val channels = found.map { it.subId }.distinct()
        val open = Intent(ctx, studio.primeminutes.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_CHANNEL, channels.singleOrNull() ?: "")
        val pi = PendingIntent.getActivity(ctx, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = ctx.resources.getQuantityString(R.plurals.bg_check_found, found.size, found.size)
        val lines = found.map { "${it.channel} · ${it.episode}" }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.setSummaryText("+${lines.size - 6}")
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_primeminutes)
            .setContentTitle(title)
            .setContentText(if (channels.size == 1) found.first().channel else lines.first())
            .setStyle(style)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n) }
    }

    /** One run: every active subscription checked one after the other inside the time box, then the diff notified. */
    suspend fun run(ctx: Context): List<Found> {
        if (!enabled(ctx)) return emptyList()
        val before = Subscriptions.load(ctx)
        withTimeoutOrNull(TIME_BOX_MS) { runCatching { SubscriptionActions.checkAll(ctx) } }
        return diff(before, Subscriptions.load(ctx)).also { notify(ctx, it) }
    }
}

class NewEpisodesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { BackgroundCheck.run(applicationContext); return Result.success() }
}
