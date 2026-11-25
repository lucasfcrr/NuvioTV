package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal fun mergeProgressProjectionWithRetainedLocal(
    providerEntries: List<WatchProgress>,
    localEntries: List<WatchProgress>,
    retainsLocalProgress: (String) -> Boolean
): List<WatchProgress> {
    // Fork: the newest wins. The TV's own progress is live; the provider (Trakt) catches up later and is
    // the backup and the phone's way in. So for the same episode the entry watched last is kept, and a
    // local entry the provider doesn't have yet counts when it is newer than all the provider knows of
    // that title (the provider used to win always, so the TV showed Trakt's older state until it synced).
    val localByKey = localEntries.associateBy(WatchProgress::projectionKey)
    val providerKeys = providerEntries.mapTo(mutableSetOf(), WatchProgress::projectionKey)
    val providerLatestByContent = providerEntries.groupBy(WatchProgress::contentId)
        .mapValues { (_, entries) -> entries.maxOf(WatchProgress::lastWatched) }
    return buildList {
        providerEntries.forEach { remote ->
            val local = localByKey[remote.projectionKey()]
            add(if (local != null && local.lastWatched > remote.lastWatched) local else remote)
        }
        localEntries.forEach { progress ->
            if (progress.projectionKey() in providerKeys) return@forEach
            val newerThanProvider = progress.lastWatched > (providerLatestByContent[progress.contentId] ?: Long.MIN_VALUE)
            if (retainsLocalProgress(progress.contentId) || newerThanProvider) add(progress)
        }
    }.sortedByDescending(WatchProgress::lastWatched)
}

private fun WatchProgress.projectionKey() = Triple(contentId, season, episode)

internal fun mergeWatchedEpisodeProjection(
    providerEpisodes: Map<String, Set<Pair<Int, Int>>>,
    localItems: List<WatchedItem>,
    retainsLocalWatchedEpisode: (WatchedItem) -> Boolean
): Map<String, Set<Pair<Int, Int>>> {
    val merged = providerEpisodes.mapValuesTo(linkedMapOf()) { (_, episodes) -> episodes.toMutableSet() }
    localItems.forEach { item ->
        val season = item.season
        val episode = item.episode
        if (season != null && episode != null && retainsLocalWatchedEpisode(item)) {
            merged.getOrPut(item.contentId, ::linkedSetOf).add(season to episode)
        }
    }
    return merged
}
