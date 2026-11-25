package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleReleaseRankingTest {
    private fun sub(id: String, label: String?) =
        Subtitle(id = id, url = "u$id", lang = "pob", addonName = "a", addonLogo = null, label = label)

    @Test
    fun `a WEB release outranks a BluRay one for a WEB file`() {
        val bluray = sub("1", "Succession.S01E04.1080p.BluRay.x265-RARBG • opensubtitles")
        val web = sub("2", "Succession.S01E04.WEB.H264-DEFLATE • opensubtitles")
        val ranked = rankAddonSubtitlesForRelease(
            listOf(bluray, web),
            "Succession.S01E04.2160p.MAX.WEB-DL.DDPA5.1.HDR.DV.HEVC-SPAMKiNGS-AsRequested"
        )
        assertEquals(listOf(web, bluray), ranked)
    }

    @Test
    fun `the same group and service win`() {
        val amzn = sub("1", "Show.S01E01.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb • opensubtitles")
        val max = sub("2", "Show.S01E01.1080p.MAX.WEB-DL.DDP5.1.H.264-FLUX • opensubtitles")
        val ranked = rankAddonSubtitlesForRelease(listOf(amzn, max), "Show.S01E01.2160p.MAX.WEB-DL.DDP5.1.HEVC-FLUX.mkv")
        assertEquals(listOf(max, amzn), ranked)
    }

    @Test
    fun `the community addon's release id is read too`() {
        val plain = sub("9", "OpenSubtitles.com")
        val named = sub("Show.S01E01.WEB.H264-DEFLATE_pob", "OpenSubtitles.com")
        val ranked = rankAddonSubtitlesForRelease(listOf(plain, named), "Show.S01E01.1080p.WEB.H264-DEFLATE.mkv")
        assertEquals(listOf(named, plain), ranked)
    }

    @Test
    fun `no release info keeps the addons' order`() {
        val a = sub("1", null)
        val b = sub("2", "Português")
        assertEquals(listOf(a, b), rankAddonSubtitlesForRelease(listOf(a, b), "Show.S01E01.WEB-DL-FLUX.mkv"))
        assertEquals(listOf(a, b), rankAddonSubtitlesForRelease(listOf(a, b), null))
    }

    @Test
    fun `the file's group is read past the AsRequested tag`() {
        assertEquals(
            "SPAMKINGS",
            ReleaseTraits.of("Succession.S01E03.2160p.MAX.WEB-DL.DDPA5.1.HDR.DV.HEVC-SPAMKiNGS-AsRequested")?.group
        )
    }
}
