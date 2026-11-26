package com.nuvio.tv.core.player

import android.util.Log
import java.io.File

/**
 * Fork: watches the process's virtual address space (VmSize) and decides how big the forward
 * buffer may be. A 32-bit TV dies when mmap fails (4K DV + conversion + a large buffer crashed at
 * ~190 MB of heap) long before RAM runs out, so the buffer has to follow address space, not RAM.
 *
 * SHADOW MODE: it only logs what it would do ("VMGOV ..."); nothing is applied yet. The log is
 * used to check, against real sessions and a forced crash, that it would react in time.
 */
object AddressSpaceGovernor {
    private const val TAG = "VMGOV"

    /** Above this the governor would start shrinking the buffer budget. */
    const val SOFT_LIMIT_MB = 2_700L

    /** Above this it would drop the budget to the floor at once. */
    const val HARD_LIMIT_MB = 2_800L

    /** Never shrink the buffer budget below this. */
    const val FLOOR_BUDGET_MB = 48L

    /** How much address space each MB of buffer is assumed to take back when released. */
    private const val SHRINK_STEP_MB = 32L

    private const val SAMPLE_INTERVAL_MS = 2_000L

    private var lastSampleAt = 0L
    private var lastDecision: String? = null

    data class Sample(val vmSizeMb: Long, val vmPeakMb: Long)

    fun read(): Sample? = runCatching {
        var size = -1L
        var peak = -1L
        File("/proc/self/status").forEachLine { line ->
            when {
                line.startsWith("VmSize:") -> size = line.filter { it.isDigit() }.toLong() / 1024
                line.startsWith("VmPeak:") -> peak = line.filter { it.isDigit() }.toLong() / 1024
            }
        }
        if (size < 0) null else Sample(size, peak)
    }.getOrNull()

    /** Budget (MB) the governor would allow, given the configured budget and what is allocated now. */
    fun wouldAllowBudgetMb(sample: Sample, configuredBudgetMb: Long, allocatedMb: Long): Long = when {
        sample.vmSizeMb >= HARD_LIMIT_MB -> FLOOR_BUDGET_MB
        sample.vmSizeMb >= SOFT_LIMIT_MB -> (allocatedMb - SHRINK_STEP_MB).coerceIn(FLOOR_BUDGET_MB, configuredBudgetMb)
        else -> configuredBudgetMb
    }

    /** Called from the playback tick; samples every 2 s and logs each change of decision. */
    fun tick(now: Long, configuredBudgetMb: Long, allocatedMb: Long, bufferedAheadSec: Long) {
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return
        lastSampleAt = now
        val sample = read() ?: return
        val allow = wouldAllowBudgetMb(sample, configuredBudgetMb, allocatedMb)
        val zone = when {
            sample.vmSizeMb >= HARD_LIMIT_MB -> "HARD"
            sample.vmSizeMb >= SOFT_LIMIT_MB -> "SOFT"
            else -> "OK"
        }
        val decision = "$zone:$allow"
        if (decision != lastDecision || zone != "OK") {
            Log.i(
                TAG,
                "shadow zone=$zone vm=${sample.vmSizeMb}MB peak=${sample.vmPeakMb}MB " +
                    "alloc=${allocatedMb}MB budget=${configuredBudgetMb}MB wouldAllow=${allow}MB ahead=${bufferedAheadSec}s"
            )
            lastDecision = decision
        }
    }

    fun reset() {
        lastSampleAt = 0L
        lastDecision = null
    }
}
