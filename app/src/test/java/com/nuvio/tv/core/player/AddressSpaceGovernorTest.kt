package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressSpaceGovernorTest {
    private fun s(vm: Long) = AddressSpaceGovernor.Sample(vm, vm)

    @Test
    fun `below the soft limit the configured budget stands`() {
        assertEquals(132L, AddressSpaceGovernor.wouldAllowBudgetMb(s(2_620), 132, 120))
    }

    @Test
    fun `in the soft zone the budget steps below what is allocated`() {
        assertEquals(88L, AddressSpaceGovernor.wouldAllowBudgetMb(s(2_720), 302, 120))
    }

    @Test
    fun `the soft step never goes under the floor or over the configured budget`() {
        assertEquals(48L, AddressSpaceGovernor.wouldAllowBudgetMb(s(2_720), 302, 60))
        assertEquals(132L, AddressSpaceGovernor.wouldAllowBudgetMb(s(2_720), 132, 300))
    }

    @Test
    fun `past the hard limit it drops to the floor`() {
        assertEquals(48L, AddressSpaceGovernor.wouldAllowBudgetMb(s(2_850), 302, 250))
    }
}
