package com.ganesan.m175otg.print

import com.ganesan.m175otg.net.EngineQueue
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class EngineQueueTest {

    @Test
    fun fifoOrder() {
        val q = EngineQueue()
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        val f1 = q.submit("a") { seen.add("a"); 1 }
        val f2 = q.submit("b") { seen.add("b"); 2 }
        val f3 = q.submit("c") { seen.add("c"); 3 }
        // Drain like the service worker does.
        assertEquals("a", q.headLabel)
        repeat(3) { assertTrue(q.step()) }
        assertEquals(listOf("a", "b", "c"), seen)
        assertEquals(1, f1.get(2, TimeUnit.SECONDS))
        assertEquals(3, f3.get(2, TimeUnit.SECONDS))
        assertEquals(0, q.waiting)
    }

    @Test
    fun failurePropagatesAndLaneSurvives() {
        val q = EngineQueue()
        val bad = q.submit("bad") { throw java.io.IOException("nope") }
        val good = q.submit("good") { 42 }
        q.step()
        q.step()
        try {
            bad.get(2, TimeUnit.SECONDS)
            fail("expected exception")
        } catch (e: java.util.concurrent.ExecutionException) {
            assertTrue(e.cause is java.io.IOException)
        }
        assertEquals(42, good.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun waitingCountsQueuedOnly() {
        val q = EngineQueue()
        q.submit("a") { 1 }
        q.submit("b") { 2 }
        assertEquals(2, q.waiting)
        q.step()
        assertEquals(1, q.waiting)
    }
}
