package com.nuvio.tv.core.player

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Fork: watches the process's virtual address space (VmSize) and decides how big the forward
 * buffer may be. A 32-bit TV dies when mmap fails (4K DV + conversion + a large buffer crashed at
 * ~190 MB of heap) long before RAM runs out, so the buffer has to follow address space, not RAM.
 *
 * Default is SHADOW: it only logs what it would do ("VMGOV ..."). A test file in the app's
 * external files dir (`fork_gov.txt`: "live soft=2700 hard=2800") switches it to LIVE and/or
 * changes the limits, and `fork_buffer_mb.txt` overrides the buffer size, so the thresholds can be
 * proven against a forced crash without a rebuild. Without those files nothing changes.
 */
@UnstableApi
object AddressSpaceGovernor {
    private const val TAG = "VMGOV"

    const val DEFAULT_SOFT_LIMIT_MB = 2_700L
    const val DEFAULT_HARD_LIMIT_MB = 2_800L

    /** Never shrink the buffer budget below this. */
    const val FLOOR_BUDGET_MB = 48L

    /** How far below the current allocation a soft-zone shrink sets the budget. */
    private const val SHRINK_STEP_MB = 32L

    /** Back under (soft - this) before the full budget is given back. */
    private const val RESTORE_MARGIN_MB = 150L

    private const val SAMPLE_INTERVAL_MS = 2_000L

    @Volatile var live = false
        private set
    @Volatile var softLimitMb = DEFAULT_SOFT_LIMIT_MB
        private set
    @Volatile var hardLimitMb = DEFAULT_HARD_LIMIT_MB
        private set

    private var lastSampleAt = 0L
    private var lastDecision: String? = null
    private var appliedBudgetMb = -1L   // -1 = no override in force

    data class Sample(val vmSizeMb: Long, val vmPeakMb: Long)

    /** Reads the test files once per playback. Missing files keep the shadow defaults. */
    fun configure(context: Context) {
        live = false
        softLimitMb = DEFAULT_SOFT_LIMIT_MB
        hardLimitMb = DEFAULT_HARD_LIMIT_MB
        runCatching {
            val f = File(context.getExternalFilesDir(null), "fork_gov.txt")
            if (f.exists()) {
                val text = f.readText()
                live = Regex("\\blive\\b").containsMatchIn(text)
                Regex("soft=(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { softLimitMb = it }
                Regex("hard=(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { hardLimitMb = it }
            }
        }
        reset()
        Log.i(TAG, "configured mode=${if (live) "LIVE" else "SHADOW"} soft=${softLimitMb}MB hard=${hardLimitMb}MB")
    }

    /** Test override of the buffer size (MB), or null. */
    fun testBufferMb(context: Context): Int? = runCatching {
        val f = File(context.getExternalFilesDir(null), "fork_buffer_mb.txt")
        if (f.exists()) f.readText().trim().toIntOrNull() else null
    }.getOrNull()

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

    /**
     * Budget (MB) allowed for [sample]. [currentlyReducedToMb] adds hysteresis: once shrunk, the
     * full budget only comes back well below the soft limit.
     */
    fun wouldAllowBudgetMb(
        sample: Sample,
        configuredBudgetMb: Long,
        allocatedMb: Long,
        currentlyReducedToMb: Long = -1L,
        soft: Long = DEFAULT_SOFT_LIMIT_MB,
        hard: Long = DEFAULT_HARD_LIMIT_MB,
    ): Long = when {
        sample.vmSizeMb >= hard -> FLOOR_BUDGET_MB
        sample.vmSizeMb >= soft ->
            (allocatedMb - SHRINK_STEP_MB).coerceIn(FLOOR_BUDGET_MB, configuredBudgetMb)
                .let { if (currentlyReducedToMb > 0) minOf(it, currentlyReducedToMb) else it }
        currentlyReducedToMb > 0 && sample.vmSizeMb >= soft - RESTORE_MARGIN_MB -> currentlyReducedToMb
        else -> configuredBudgetMb
    }

    /** Called from the playback tick; samples every 2 s, logs each change, applies it when LIVE. */
    fun tick(now: Long, lc: BitrateAwareLoadControl?, player: ExoPlayer?, allocatedMb: Long, bufferedAheadSec: Long) {
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return
        lastSampleAt = now
        val sample = read() ?: return
        val configuredMb = lc?.constructedBudgetBytes()?.div(1024L * 1024L) ?: return
        val allow = wouldAllowBudgetMb(sample, configuredMb, allocatedMb, appliedBudgetMb, softLimitMb, hardLimitMb)
        val zone = when {
            sample.vmSizeMb >= hardLimitMb -> "HARD"
            sample.vmSizeMb >= softLimitMb -> "SOFT"
            else -> "OK"
        }
        if (live) {
            val target = if (allow >= configuredMb) -1L else allow
            if (target != appliedBudgetMb) {
                lc.setBudgetBytesOverride(if (target < 0) -1L else target * 1024L * 1024L, player)
                appliedBudgetMb = target
                Log.i(TAG, "live applied budget=${if (target < 0) "full(${configuredMb}MB)" else "${target}MB"} vm=${sample.vmSizeMb}MB")
            }
        }
        val decision = "$zone:$allow"
        if (decision != lastDecision || zone != "OK") {
            Log.i(
                TAG,
                "${if (live) "live" else "shadow"} zone=$zone vm=${sample.vmSizeMb}MB peak=${sample.vmPeakMb}MB " +
                    "alloc=${allocatedMb}MB budget=${configuredMb}MB allow=${allow}MB ahead=${bufferedAheadSec}s"
            )
            lastDecision = decision
        }
    }

    fun reset() {
        lastSampleAt = 0L
        lastDecision = null
        appliedBudgetMb = -1L
    }
}
