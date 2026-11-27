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
    fun `once reduced it holds until a margin under the soft limit`() {
        assertEquals(88L, AddressSpaceGovernor.wouldAllowBudgetMb(s(2_650), 302, 80, currentlyReducedToMb = 88))
        assertEquals(302L, AddressSpaceGovernor.wouldAllowBudgetMb(s(2_600), 302, 80, currentlyReducedToMb = 88))
    }

    @Test
    fun `a brief spike over the soft limit is ignored, a sustained one is not`() {
        val soft = 2_700L
        val hard = 2_800L
        // First seconds of a 4K DV start touch 2.7 GB: clamped under the soft limit.
        assertEquals(2_699L, AddressSpaceGovernor.debounced(s(2_720), 1, soft, hard).vmSizeMb)
        assertEquals(2_699L, AddressSpaceGovernor.debounced(s(2_720), 2, soft, hard).vmSizeMb)
        assertEquals(2_720L, AddressSpaceGovernor.debounced(s(2_720), AddressSpaceGovernor.SUSTAIN_SAMPLES, soft, hard).vmSizeMb)
        // Below the soft limit and past the hard limit pass through untouched.
        assertEquals(2_640L, AddressSpaceGovernor.debounced(s(2_640), 0, soft, hard).vmSizeMb)
        assertEquals(2_850L, AddressSpaceGovernor.debounced(s(2_850), 1, soft, hard).vmSizeMb)
    }

    @Test
    fun `past the hard limit it drops to the floor`() {
        assertEquals(48L, AddressSpaceGovernor.wouldAllowBudgetMb(s(2_850), 302, 250))
    }
}
