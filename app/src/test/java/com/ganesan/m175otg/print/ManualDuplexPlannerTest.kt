package com.ganesan.m175otg.print

import org.junit.Assert.*
import org.junit.Test

class ManualDuplexPlannerTest {

    @Test
    fun planEvensReversedOddsForward() {
        val p = ManualDuplexPlanner.plan(8)
        assertEquals(listOf(8, 6, 4, 2), p.pass1)
        assertEquals(listOf(1, 3, 5, 7), p.pass2)
    }

    @Test
    fun planOddCount() {
        val p = ManualDuplexPlanner.plan(5)
        assertEquals(listOf(4, 2), p.pass1)
        assertEquals(listOf(1, 3, 5), p.pass2)
    }

    @Test
    fun idleWhenReadyVariants() {
        assertTrue(ManualDuplexPlanner.isEngineIdle("Ready"))
        assertTrue(ManualDuplexPlanner.isEngineIdle("READY"))
        assertTrue(ManualDuplexPlanner.isEngineIdle("Ready to print"))
        assertTrue(ManualDuplexPlanner.isEngineIdle("Sleep mode"))
        assertTrue(ManualDuplexPlanner.isEngineIdle("Idle"))
        assertTrue(ManualDuplexPlanner.isEngineIdle("Power Save"))
    }

    @Test
    fun busyWhileJobLike() {
        assertFalse(ManualDuplexPlanner.isEngineIdle("Printing document"))
        assertFalse(ManualDuplexPlanner.isEngineIdle("Printing..."))
        assertFalse(ManualDuplexPlanner.isEngineIdle("Processing"))
        assertFalse(ManualDuplexPlanner.isEngineIdle("Copying"))
        assertFalse(ManualDuplexPlanner.isEngineIdle("Warming up"))
        assertFalse(ManualDuplexPlanner.isEngineIdle("Calibrating"))
        assertFalse(ManualDuplexPlanner.isEngineIdle(""))
        assertFalse(ManualDuplexPlanner.isEngineIdle("Paper jam"))
    }
}
