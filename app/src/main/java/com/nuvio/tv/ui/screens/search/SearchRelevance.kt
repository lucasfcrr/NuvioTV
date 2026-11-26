package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.data.remote.api.TmdbMultiSearchResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import java.text.Normalizer

/**
 * Fork: search results ordered by relevance, with TMDB's multi search as the judge.
 *
 * Addon catalogs come back one row per type, in manifest order, so "The Chosen" listed the movies
 * first and the series (the title people mean) second. TMDB ranks movies and series together by
 * text match and popularity; each addon result is matched to a TMDB hit (same type, same title in
 * either language, year within one) and rows and items are ordered by the best rank they hold.
 * Whatever TMDB can't match keeps the addon's own order, after the matched ones.
 */
internal data class TmdbHit(
    val isSeries: Boolean,
    val titles: Set<String>,
    val year: Int?,
    val rank: Int,
)

internal object SearchRelevance {
    /** Movies and series only, in TMDB's order. People and unknown types are dropped. */
    fun hitsFrom(results: List<TmdbMultiSearchResult>): List<TmdbHit> =
        results.filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .mapIndexed { index, r ->
                val series = r.mediaType == "tv"
                TmdbHit(
                    isSeries = series,
                    titles = listOfNotNull(r.title, r.name, r.originalTitle, r.originalName)
                        .map(::normalize).filter { it.isNotEmpty() }.toSet(),
                    year = (if (series) r.firstAirDate else r.releaseDate)?.take(4)?.toIntOrNull(),
                    rank = index,
                )
            }

    /** TMDB rank of [item], or null when no hit matches it. */
    fun rankOf(item: MetaPreview, hits: List<TmdbHit>): Int? {
        val series = item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)
        val title = normalize(item.name)
        if (title.isEmpty()) return null
        val year = yearOf(item)
        return hits.firstOrNull { hit ->
            hit.isSeries == series && title in hit.titles &&
                (year == null || hit.year == null || kotlin.math.abs(year - hit.year) <= 1)
        }?.rank
    }

    /** Catalog id of the merged "best results" row. The screen keys off it (no See All, no type suffix). */
    const val MERGED_CATALOG_ID = "__best_results__"

    /**
     * One list instead of one row per type: every loaded row of an addon is merged into a single
     * [title] row, movies and series together, ordered by TMDB rank. What TMDB does not match
     * follows, taking turns between the addon's rows so each keeps its own order. While only one of
     * an addon's rows has loaded, it is just reordered and the others keep their shimmer; the
     * merge happens as soon as a second one arrives.
     */
    fun unify(rows: List<CatalogRow>, hits: List<TmdbHit>, title: String): List<CatalogRow> {
        if (hits.isEmpty()) return rows
        fun isPlaceholder(row: CatalogRow) =
            row.isLoading && row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
        val result = mutableListOf<CatalogRow>()
        val doneGroups = mutableSetOf<String>()
        for (row in rows) {
            val group = row.addonBaseUrl
            if (!doneGroups.add(group)) continue
            val inGroup = rows.filter { it.addonBaseUrl == group }
            val loaded = inGroup.filter { !isPlaceholder(it) && it.items.isNotEmpty() }
            if (loaded.size < 2) {
                result += order(inGroup, hits)
                continue
            }
            val ranked = mutableListOf<Pair<Int, MetaPreview>>()
            val unmatchedByRow = loaded.map { mutableListOf<MetaPreview>() }
            val seen = mutableSetOf<String>()
            loaded.forEachIndexed { rowIndex, r ->
                r.items.forEach { item ->
                    if (!seen.add("${item.apiType}:${item.id}")) return@forEach
                    val rank = rankOf(item, hits)
                    if (rank != null) ranked += rank to item else unmatchedByRow[rowIndex] += item
                }
            }
            val merged = ranked.sortedBy { it.first }.map { it.second }.toMutableList()
            var position = 0
            while (unmatchedByRow.any { position < it.size }) {
                unmatchedByRow.forEach { it.getOrNull(position)?.let(merged::add) }
                position++
            }
            val first = loaded.first()
            result += first.copy(
                catalogId = MERGED_CATALOG_ID,
                catalogName = title,
                items = merged,
                isLoading = loaded.any { it.isLoading } || inGroup.any(::isPlaceholder),
                hasMore = false,
                supportsSkip = false,
                nextSkip = 0,
                extraArgs = emptyMap(),
            )
        }
        return result
    }

    /** Rows ordered by the best-ranked item they hold; items inside each row likewise. */
    fun order(rows: List<CatalogRow>, hits: List<TmdbHit>): List<CatalogRow> {
        if (hits.isEmpty()) return rows
        val sortedItems = rows.map { row ->
            if (row.isLoading || row.items.size < 2) row else {
                val ranks = row.items.map { rankOf(it, hits) }
                val order = row.items.indices.sortedWith(
                    compareBy<Int> { ranks[it] ?: Int.MAX_VALUE }.thenBy { it }
                )
                row.copy(items = order.map { row.items[it] })
            }
        }
        val rowRank = sortedItems.map { row ->
            if (row.isLoading) null else row.items.mapNotNull { rankOf(it, hits) }.minOrNull()
        }
        val order = sortedItems.indices.sortedWith(
            compareBy<Int> { rowRank[it] ?: Int.MAX_VALUE }.thenBy { it }
        )
        return order.map { sortedItems[it] }
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("[\\p{M}]+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .removePrefix("the ")

    private fun yearOf(item: MetaPreview): Int? =
        Regex("(19|20)[0-9]{2}").find(item.releaseInfo.orEmpty())?.value?.toIntOrNull()
            ?: item.released?.take(4)?.toIntOrNull()
}
