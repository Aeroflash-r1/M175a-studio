package com.m175astudio.net

import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

/**
 * Single-lane FIFO for the printer engine.
 *
 * The M175a is single-tasked: overlapping USB jobs corrupt each other, so
 * every hosted job (remote IPP print, remote eSCL scan page) takes a ticket
 * and the engine serves tickets in order. Submitters block on the returned
 * future instead of getting an instant "busy" rejection — an office queue
 * of phones simply waits its turn.
 *
 * Pure JVM (no Android APIs) so it is unit-testable. The single worker is
 * owned by PhoneBridgeService; this class is only the lane + tickets.
 */
class EngineQueue {

    /** A queued unit of engine work. */
    class Ticket<T>(val label: String, internal val work: () -> T) {
        internal val future = CompletableFuture<T>()

        internal fun run() {
            try {
                future.complete(work())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
    }

    private val lane = LinkedBlockingQueue<Ticket<*>>()

    /** Enqueue [work] and get the future to await. FIFO, unbounded. */
    fun <T> submit(label: String, work: () -> T): CompletableFuture<T> {
        val t = Ticket(label, work)
        lane.put(t)
        return t.future
    }

    /**
     * Worker side: take the next ticket (blocks). Returns null only when
     * the thread is interrupted (service shutdown).
     */
    fun take(): Ticket<*>? = try {
        lane.take()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    /** Jobs waiting (excludes the one running). Shown in /api/status. */
    val waiting: Int get() = lane.size

    /** Label of the head ticket without consuming (notification use). */
    val headLabel: String? get() = lane.peek()?.label

    /**
     * Worker side: take the next ticket and run it (completing its future).
     * Returns false only on shutdown interrupt — the worker loop ends then.
     */
    fun step(): Boolean {
        val t = take() ?: return false
        t.run()
        return true
    }
}
