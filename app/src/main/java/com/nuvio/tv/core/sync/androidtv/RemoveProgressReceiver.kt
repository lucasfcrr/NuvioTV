package com.nuvio.tv.core.sync.androidtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fork addition: lets the home-screen launcher remove a Continue Watching item without opening
 * the app. It does what the Home row's "Remove" does: it drops the progress (Trakt playback and
 * local), dismisses the Next Up card, and removes the Watch Next entry.
 *
 * adb shell am broadcast -a com.nuvio.tv.action.REMOVE_PROGRESS -n com.nuvio.tv/.core.sync.androidtv.RemoveProgressReceiver --es contentId tt0903747
 */
class RemoveProgressReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun watchProgressRepository(): WatchProgressRepository
        fun traktSettingsDataStore(): TraktSettingsDataStore
        fun tvRecommendationManager(): TvRecommendationManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val contentId = intent.getStringExtra("contentId")?.trim().orEmpty()
        if (contentId.isEmpty()) return
        val season = intent.getIntExtra("season", -1).takeIf { it >= 0 }
        val episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 }

        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) {
                    runCatching { deps.tvRecommendationManager().onProgressRemoved(contentId) }
                    runCatching { deps.traktSettingsDataStore().addDismissedNextUpKey(contentId) }
                    if (season != null && episode != null) {
                        runCatching {
                            deps.traktSettingsDataStore().addDismissedNextUpKey("${contentId}_s${season}e$episode")
                        }
                    }
                    runCatching { deps.watchProgressRepository().removeProgress(contentId) }
                        .onFailure { Log.w(TAG, "removeProgress failed for $contentId", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.nuvio.tv.action.REMOVE_PROGRESS"
        private const val TAG = "RemoveProgressReceiver"
    }
}
