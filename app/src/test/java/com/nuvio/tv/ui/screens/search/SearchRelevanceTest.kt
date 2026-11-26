package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.data.remote.api.TmdbMultiSearchResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRelevanceTest {
    private fun item(id: String, name: String, type: String, year: String) = MetaPreview(
        id = id, type = ContentType.fromString(type), rawType = type, name = name, poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
        releaseInfo = year, imdbRating = null, genres = emptyList()
    )

    private fun row(type: String, vararg items: MetaPreview) = CatalogRow(
        addonId = "meta", addonName = "AIOMetadata", addonBaseUrl = "u", catalogId = "search-$type",
        catalogName = type, type = ContentType.fromString(type), rawType = type, items = items.toList(),
        isLoading = false, hasMore = false, currentPage = 0, supportsSkip = false, skipStep = 0, extraArgs = emptyMap()
    )

    private fun tmdb(type: String, title: String, year: String, original: String = title) = TmdbMultiSearchResult(
        id = 1, mediaType = type,
        title = if (type == "movie") title else null, name = if (type == "tv") title else null,
        originalTitle = if (type == "movie") original else null, originalName = if (type == "tv") original else null,
        releaseDate = if (type == "movie") "$year-01-01" else null, firstAirDate = if (type == "tv") "$year-01-01" else null
    )

    private val chosenHits = SearchRelevance.hitsFrom(
        listOf(
            tmdb("tv", "The Chosen: Os Escolhidos", "2019", original = "The Chosen"),
            tmdb("tv", "As Aventuras Escolhidas", "2025"),
            tmdb("movie", "The Chosen: Última Ceia", "2025"),
            tmdb("person", "Eight The Chosen One", ""),
        )
    )

    @Test
    fun `the series row goes first when TMDB ranks the series first`() {
        val movies = row("movie", item("tt1", "The Chosen: Última Ceia", "movie", "2025"), item("tt2", "A Escolha", "movie", "1981"))
        val series = row("series", item("tt3", "The Chosen", "series", "2019–"), item("tt4", "As Aventuras Escolhidas", "series", "2025"))
        val ordered = SearchRelevance.order(listOf(movies, series), chosenHits)
        assertEquals(listOf("search-series", "search-movie"), ordered.map { it.catalogId })
    }

    @Test
    fun `items inside a row follow the TMDB rank and unmatched ones keep their order after`() {
        val series = row(
            "series",
            item("tt9", "Something Else", "series", "2000"),
            item("tt4", "As Aventuras Escolhidas", "series", "2025"),
            item("tt3", "The Chosen", "series", "2019"),
            item("tt8", "Another One", "series", "2001"),
        )
        val ordered = SearchRelevance.order(listOf(series), chosenHits).single().items.map { it.id }
        assertEquals(listOf("tt3", "tt4", "tt9", "tt8"), ordered)
    }

    @Test
    fun `a movie and a series with the same title are told apart by type`() {
        val hits = SearchRelevance.hitsFrom(listOf(tmdb("tv", "Fargo", "2014"), tmdb("movie", "Fargo", "1996")))
        assertEquals(0, SearchRelevance.rankOf(item("a", "Fargo", "series", "2014"), hits))
        assertEquals(1, SearchRelevance.rankOf(item("b", "Fargo", "movie", "1996"), hits))
    }

    @Test
    fun `accents, case and a leading The do not stop a match, a far year does`() {
        val hits = SearchRelevance.hitsFrom(listOf(tmdb("movie", "Duna", "2021", original = "Dune")))
        assertEquals(0, SearchRelevance.rankOf(item("a", "DUNE", "movie", "2021"), hits))
        assertEquals(null, SearchRelevance.rankOf(item("b", "Dune", "movie", "1984"), hits))
    }

    @Test
    fun `movies and series become one list ordered by TMDB rank, then the rest take turns`() {
        val movies = row(
            "movie",
            item("m-other", "Movie Other", "movie", "2000"),
            item("tt1", "The Chosen: Última Ceia", "movie", "2025"),
            item("m-other2", "Movie Other Two", "movie", "2001"),
        )
        val series = row(
            "series",
            item("s-other", "Series Other", "series", "2002"),
            item("tt3", "The Chosen", "series", "2019–"),
            item("s-other2", "Series Other Two", "series", "2003"),
        )
        val unified = SearchRelevance.unify(listOf(movies, series), chosenHits, "Best results")
        assertEquals(1, unified.size)
        assertEquals(SearchRelevance.MERGED_CATALOG_ID, unified.single().catalogId)
        assertEquals("Best results", unified.single().catalogName)
        assertEquals(
            listOf("tt3", "tt1", "m-other", "s-other", "m-other2", "s-other2"),
            unified.single().items.map { it.id }
        )
    }

    @Test
    fun `a lone loaded row is only reordered and its still-loading sibling keeps its shimmer`() {
        val loading = row("movie", item("__placeholder_x_0", " ", "movie", "")).copy(isLoading = true)
        val series = row("series", item("a", "Something", "series", "2000"), item("tt3", "The Chosen", "series", "2019"))
        val unified = SearchRelevance.unify(listOf(loading, series), chosenHits, "Best results")
        assertEquals(listOf("series", "movie"), unified.map { it.catalogName })
        assertEquals(listOf("tt3", "a"), unified.first().items.map { it.id })
    }

    @Test
    fun `an item present in two rows is listed once`() {
        val a = row("movie", item("tt1", "The Chosen: Última Ceia", "movie", "2025"))
        val b = row("series", item("tt3", "The Chosen", "series", "2019"), item("tt3", "The Chosen", "series", "2019"))
        val unified = SearchRelevance.unify(listOf(a, b), chosenHits, "Best results")
        assertEquals(listOf("tt3", "tt1"), unified.single().items.map { it.id })
    }

    @Test
    fun `no hits leaves the rows untouched`() {
        val rows = listOf(row("movie", item("tt1", "X", "movie", "2000")), row("series", item("tt2", "Y", "series", "2000")))
        assertEquals(rows, SearchRelevance.order(rows, emptyList()))
        assertEquals(rows, SearchRelevance.unify(rows, emptyList(), "Best results"))
    }
}
