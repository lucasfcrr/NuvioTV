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
 * Default is LIVE (see [DEFAULT_LIVE]); it logs every decision ("VMGOV ..."). A test file in the
 * app's external files dir (`fork_gov.txt`: "shadow", or "live soft=2700 hard=2800") switches the
 * mode and/or changes the limits, and `fork_buffer_mb.txt` overrides the buffer size, so the
 * thresholds can be proven against a forced crash without a rebuild.
 */
@UnstableApi
object AddressSpaceGovernor {
    private const val TAG = "VMGOV"

    /**
     * LIVE since 27/11/2026: it only acts past the soft limit (2.7 GB), which normal 4K DV
     * (2.6 GB, buffer full) does not reach. A `fork_gov.txt` containing "shadow" turns it back
     * into log-only, "live soft=.. hard=.." sets test limits.
     */
    const val DEFAULT_LIVE = true

    const val DEFAULT_SOFT_LIMIT_MB = 2_700L
    const val DEFAULT_HARD_LIMIT_MB = 2_800L

    /** Never shrink the buffer budget below this. */
    const val FLOOR_BUDGET_MB = 48L

    /** How far below the current allocation a soft-zone shrink sets the budget. */
    private const val SHRINK_STEP_MB = 32L

    /**
     * Back under (soft - this) before the full budget is given back. Small on purpose: 4K Dolby
     * Vision plays at 2.6 GB with the buffer full, so a wide margin would keep the buffer shrunk
     * for a whole film after one brief peak (measured on the TV, 27/11).
     */
    private const val RESTORE_MARGIN_MB = 60L

    /** Samples (2 s each) VmSize must stay at or above the soft limit before the buffer shrinks. */
    const val SUSTAIN_SAMPLES = 3

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
    private var overSoftSamples = 0

    data class Sample(val vmSizeMb: Long, val vmPeakMb: Long)

    // Persistent trace: survives TV power-off (logcat doesn't), read later with adb from
    // /sdcard/Android/data/com.nuvio.tv/files/fork_vmgov.log. One line per 2 s sample while
    // playing (~75 B/s, ~18 h per 5 MB), rotated to .1 at 5 MB.
    private const val TRACE_FILE = "fork_vmgov.log"
    private const val TRACE_MAX_BYTES = 5L * 1024L * 1024L
    private val traceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "vmgov-trace").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var traceDir: File? = null
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)

    private fun trace(line: String) {
        val dir = traceDir ?: return
        val time = System.currentTimeMillis()
        traceExecutor.execute {
            runCatching {
                val f = File(dir, TRACE_FILE)
                if (f.length() > TRACE_MAX_BYTES) {
                    val old = File(dir, "$TRACE_FILE.1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText("${stamp.format(java.util.Date(time))} pid=${android.os.Process.myPid()} $line\n")
            }
        }
    }

    /** Reads the test files once per playback. Missing files keep the shadow defaults. */
    fun configure(context: Context) {
        traceDir = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        live = DEFAULT_LIVE
        softLimitMb = DEFAULT_SOFT_LIMIT_MB
        hardLimitMb = DEFAULT_HARD_LIMIT_MB
        runCatching {
            val f = File(context.getExternalFilesDir(null), "fork_gov.txt")
            if (f.exists()) {
                val text = f.readText()
                if (Regex("\\bshadow\\b").containsMatchIn(text)) live = false
                if (Regex("\\blive\\b").containsMatchIn(text)) live = true
                Regex("soft=(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { softLimitMb = it }
                Regex("hard=(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { hardLimitMb = it }
            }
        }
        reset()
        val header = "configured mode=${if (live) "LIVE" else "SHADOW"} soft=${softLimitMb}MB hard=${hardLimitMb}MB"
        Log.i(TAG, header)
        trace("START $header vm=${read()?.vmSizeMb}MB")
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

    /**
     * A brief spike over the soft limit (the first seconds of a 4K DV start touch ~2.7 GB) must not
     * shrink the buffer: until [overSoftSamples] reaches [SUSTAIN_SAMPLES] the sample is clamped
     * just under the soft limit. The hard limit is never delayed.
     */
    fun debounced(sample: Sample, overSoftSamples: Int, soft: Long, hard: Long): Sample =
        if (sample.vmSizeMb >= hard || overSoftSamples >= SUSTAIN_SAMPLES || sample.vmSizeMb < soft) sample
        else sample.copy(vmSizeMb = soft - 1)

    /** Called from the playback tick; samples every 2 s, logs each change, applies it when LIVE. */
    fun tick(now: Long, lc: BitrateAwareLoadControl?, player: ExoPlayer?, allocatedMb: Long, bufferedAheadSec: Long) {
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return
        lastSampleAt = now
        val raw = read() ?: return
        overSoftSamples = if (raw.vmSizeMb >= softLimitMb) overSoftSamples + 1 else 0
        val sample = debounced(raw, overSoftSamples, softLimitMb, hardLimitMb)
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
                val applied = "live applied budget=${if (target < 0) "full(${configuredMb}MB)" else "${target}MB"} vm=${sample.vmSizeMb}MB"
                Log.i(TAG, applied)
                trace(applied)
            }
        }
        val runtime = Runtime.getRuntime()
        val heapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        trace(
            "zone=$zone vm=${sample.vmSizeMb} peak=${sample.vmPeakMb} heap=$heapMb alloc=$allocatedMb " +
                "budget=$configuredMb allow=$allow ahead=${bufferedAheadSec}s"
        )
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
        overSoftSamples = 0
    }
}
