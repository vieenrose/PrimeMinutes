package studio.primeminutes.service

import android.content.Intent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Requests too big for an Intent (a transcript, a whole session) handed from the UI to the service:
 * [post] keeps one under a ticket the intent carries ([TranscriptionService.EXTRA_REQUEST]), and the
 * service [take]s that one. A single shared slot lost the first of two requests sent before the
 * service read them (two quick exports, an edit persisted while another was still queued).
 */
class Mailbox<T : Any> {
    private val next = AtomicLong(1)
    private val slots = ConcurrentHashMap<Long, T>()

    /** Keep [value]; returns its ticket for the intent. */
    fun post(value: T): Long = next.getAndIncrement().also { slots[it] = value }

    /** The request [intent] carries, removed; null when it has none (or it was already taken). */
    fun take(intent: Intent?): T? =
        intent?.getLongExtra(TranscriptionService.EXTRA_REQUEST, 0L)?.takeIf { it > 0 }?.let { slots.remove(it) }
}
