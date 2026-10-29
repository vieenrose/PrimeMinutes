package studio.primeminutes.online

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BackgroundCheckTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun ep(g: String) = FeedEpisode(guid = g, title = "Ep $g", audioUrl = "https://x/$g.mp3")
    private fun sub(url: String = "https://x/feed", paused: Boolean = false, seen: List<String>, eps: List<String>) =
        Subscription(feedUrl = url, title = "Show", addedAt = 100, paused = paused, seenGuids = seen, episodes = eps.map(::ep))

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        File(ctx.filesDir, "subscriptions.json").delete(); Subscriptions.resetForTest()
    }

    private fun state(): WorkInfo.State? =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(BackgroundCheck.WORK_NAME).get().firstOrNull()?.state

    @Test fun scheduleDecision() {
        val active = sub(seen = emptyList(), eps = emptyList())
        assertFalse(BackgroundCheck.shouldSchedule(false, listOf(active)))
        assertFalse(BackgroundCheck.shouldSchedule(true, emptyList()))
        assertFalse(BackgroundCheck.shouldSchedule(true, listOf(active.copy(paused = true))))
        assertTrue(BackgroundCheck.shouldSchedule(true, listOf(active.copy(paused = true), sub(url = "https://y", seen = emptyList(), eps = emptyList()))))
    }

    @Test fun requestIsDailyOnWifiWithBattery() {
        val r = BackgroundCheck.request().workSpec
        assertEquals(TimeUnit.HOURS.toMillis(24), r.intervalDuration)
        assertEquals(NetworkType.UNMETERED, r.constraints.requiredNetworkType)
        assertTrue(r.constraints.requiresBatteryNotLow())
    }

    @Test fun enqueuedOnlyWhileOnAndActiveSubscriptionExists() {
        BackgroundCheck.setEnabled(ctx, true)
        assertEquals(null, state())   // no subscription yet
        val s = sub(seen = emptyList(), eps = emptyList())
        Subscriptions.upsert(ctx, s)
        assertEquals(WorkInfo.State.ENQUEUED, state())
        Subscriptions.update(ctx) { l -> l.map { it.copy(paused = true) } }
        assertEquals(WorkInfo.State.CANCELLED, state())
        Subscriptions.update(ctx) { l -> l.map { it.copy(paused = false) } }
        assertEquals(WorkInfo.State.ENQUEUED, state())
        BackgroundCheck.setEnabled(ctx, false)
        assertEquals(WorkInfo.State.CANCELLED, state())
        BackgroundCheck.setEnabled(ctx, true)
        Subscriptions.remove(ctx, s.id)
        assertEquals(WorkInfo.State.CANCELLED, state())
    }

    @Test fun keepPolicyDoesNotReplaceTheRunningPeriod() {
        BackgroundCheck.setEnabled(ctx, true)
        Subscriptions.upsert(ctx, sub(seen = emptyList(), eps = emptyList()))
        val first = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(BackgroundCheck.WORK_NAME).get().single().id
        BackgroundCheck.sync(ctx, force = true)
        assertEquals(first, WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(BackgroundCheck.WORK_NAME).get().single().id)
    }

    @Test fun diffReportsOnlyEpisodesThisCheckFound() {
        val before = sub(seen = listOf("a", "b"), eps = listOf("b", "a", "old-unseen"))
        val after = before.copy(episodes = listOf("c", "b", "a", "old-unseen").map(::ep))
        val found = BackgroundCheck.diff(listOf(before), listOf(after))
        assertEquals(listOf("Ep c"), found.map { it.episode })
        assertEquals("Show", found.single().channel)
    }

    @Test fun diffSkipsPausedAndFollowsAMove() {
        val before = sub(seen = listOf("a"), eps = listOf("a"))
        val after = before.copy(episodes = listOf("n", "a").map(::ep))
        assertTrue(BackgroundCheck.diff(listOf(before), listOf(after.copy(paused = true))).isEmpty())
        val moved = SubscriptionLogic.rekey(after, "https://new/feed")
        val f = BackgroundCheck.diff(listOf(before), listOf(moved))
        assertEquals(moved.id, f.single().subId)
    }

    @Test fun diffIgnoresANewSubscription() {
        assertTrue(BackgroundCheck.diff(emptyList(), listOf(sub(seen = emptyList(), eps = listOf("x")))).isEmpty())
    }
}
