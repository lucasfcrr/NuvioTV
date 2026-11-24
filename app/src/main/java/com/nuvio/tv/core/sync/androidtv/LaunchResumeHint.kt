package com.nuvio.tv.core.sync.androidtv

import android.content.Intent

/**
 * Fork addition: where a Continue Watching card (Watch Next or the channel) was left, carried in its intent.
 *
 * Opened from a card on a cold start (after the TV powers on, or after an update), the app goes straight to the
 * player without ever showing Home, which is what normally loads the Trakt progress; the player then found no saved
 * position and started the title from the beginning. The card already knows the position, so the player falls back
 * to it when its own lookup comes back empty (PlayerRuntimeController.loadSavedProgressSuspend).
 */
object LaunchResumeHint {
    const val EXTRA_POSITION = "resumePositionMs"
    const val EXTRA_DURATION = "resumeDurationMs"

    data class Hint(
        val contentId: String,
        val videoId: String?,
        val season: Int?,
        val episode: Int?,
        val positionMs: Long,
        val durationMs: Long
    )

    @Volatile
    private var pending: Hint? = null

    /** Keeps the card's position from a launch intent (fresh launch or onNewIntent); ignores anything else. */
    fun capture(intent: Intent?) {
        val contentId = intent?.getStringExtra("contentId")?.takeIf { it.isNotBlank() } ?: return
        val position = intent.getLongExtra(EXTRA_POSITION, -1L)
        if (position <= 0L) {
            pending = null
            return
        }
        pending = Hint(
            contentId = contentId,
            videoId = intent.getStringExtra("videoId"),
            season = intent.getIntExtra("season", -1).takeIf { it >= 0 },
            episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 },
            positionMs = position,
            durationMs = intent.getLongExtra(EXTRA_DURATION, 0L).coerceAtLeast(0L)
        )
    }

    /** The hint for this title (and episode), once: a later playback of it uses the app's own progress. */
    fun take(contentId: String?, season: Int?, episode: Int?): Hint? {
        val hint = pending ?: return null
        if (hint.contentId != contentId) return null
        if (season != null && episode != null && (hint.season != season || hint.episode != episode)) return null
        pending = null
        return hint
    }
}

/** The position a card shows: the saved one, else the percent of the duration (as ProgramBuilder draws its bar). */
internal fun resumePositionMs(progress: com.nuvio.tv.domain.model.WatchProgress): Long = when {
    progress.position > 0L -> progress.position
    progress.duration > 0L -> progress.progressPercent?.let { (it / 100f * progress.duration).toLong() } ?: 0L
    else -> 0L
}
