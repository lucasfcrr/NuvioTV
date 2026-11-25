package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Subtitle

/**
 * Fork: addon subtitles ordered by how well their release matches the playing file, best first.
 *
 * Addons (SubMaker, community OpenSubtitles) name the release a subtitle was timed for, in the label or the id
 * ("Succession.S01E04.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb"). A subtitle from the same group, service and source
 * keeps the file's timing; a BluRay one on a WEB file is the usual reason a subtitle drifts. Ties keep the
 * addons' own order, so with no release info nothing changes.
 */
internal fun rankAddonSubtitlesForRelease(subtitles: List<Subtitle>, filename: String?): List<Subtitle> {
    if (subtitles.size < 2) return subtitles
    val file = ReleaseTraits.of(filename) ?: return subtitles
    return subtitles.withIndex()
        .sortedWith(compareByDescending<IndexedValue<Subtitle>> { subtitleReleaseScore(it.value, file) }.thenBy { it.index })
        .map { it.value }
}

internal fun subtitleReleaseScore(subtitle: Subtitle, file: ReleaseTraits): Int {
    if (subtitle.isStreamProvided) return 1_000
    val sub = ReleaseTraits.of("${subtitle.label.orEmpty()} ${subtitle.id}") ?: return 0
    var score = 0
    if (file.group != null && file.group == sub.group) score += 60
    if (file.service != null && file.service == sub.service) score += 25
    if (file.source != null && sub.source != null) score += if (file.source == sub.source) 30 else -20
    return score
}

internal data class ReleaseTraits(val source: String?, val service: String?, val group: String?) {
    companion object {
        private val SERVICES = mapOf(
            "AMZN" to "AMZN", "NF" to "NF", "MAX" to "MAX", "HMAX" to "MAX", "DSNP" to "DSNP",
            "ATVP" to "ATVP", "HULU" to "HULU", "PCOK" to "PCOK", "PMTP" to "PMTP", "STAN" to "STAN"
        )

        fun of(text: String?): ReleaseTraits? {
            if (text.isNullOrBlank()) return null
            val tokens = text.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }.toSet()
            val source = when {
                "WEB" in tokens || "WEBDL" in tokens || "WEBRIP" in tokens -> "WEB"
                tokens.any { it == "BLURAY" || it == "BDRIP" || it == "BRRIP" || it == "REMUX" || it == "BD" } -> "BLURAY"
                "HDTV" in tokens -> "HDTV"
                tokens.any { it == "DVDRIP" || it == "DVD" } -> "DVD"
                else -> null
            }
            val service = tokens.firstNotNullOfOrNull { SERVICES[it] }
            // The release group: what follows the last '-' of the release name (before any " • addon" suffix).
            val name = text.substringBefore(" • ").trim()
                .replace(Regex("(?i)\\.(mkv|mp4|avi|ts|m2ts|srt|vtt|ass)$"), "")
                .replace(Regex("_[a-zA-Z]{2,3}$"), "")   // "…-DEFLATE_pob": the language the community addon appends
                .replace(Regex("(?i)(-(AsRequested|Obfuscated|Scrambled|xpost|postbot|Rakuv\\w*))+$"), "")
            val group = name.substringAfterLast('-', "").uppercase()
                .takeIf { it.matches(Regex("[A-Z0-9]{2,20}")) && it !in setOf("DL", "RIP") }
            if (source == null && service == null && group == null) return null
            return ReleaseTraits(source, service, group)
        }
    }
}
