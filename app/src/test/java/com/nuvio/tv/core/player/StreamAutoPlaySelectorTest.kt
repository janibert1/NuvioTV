package com.nuvio.tv.core.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPlaySelectorTest {

    @Test
    fun `orderAddonStreams follows installed addon order and leaves plugins last`() {
        val plugin = addonStreams("Plugin")
        val addonB = addonStreams("AddonB")
        val addonA = addonStreams("AddonA")
        val unknown = addonStreams("UnknownPlugin")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(plugin, addonB, addonA, unknown),
            installedOrder = listOf("AddonA", "AddonB")
        )

        assertEquals(listOf(addonA, addonB, plugin, unknown), ordered)
    }

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group"
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "missing-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first ignores source and addon plugin filters`() {
        val filteredOutAddonMatch = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon-match.m3u8",
            bingeGroup = "same-group"
        )
        val allowedPluginMatch = stream(
            addonName = "PluginAllowed",
            url = "https://example.com/plugin-match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddonMatch, allowedPluginMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("AddonFilteredOut"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("PluginAllowed"),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(filteredOutAddonMatch, selected)
    }

    @Test
    fun `regex mode still works when bingeGroup missing or no match`() {
        val nonMatch = stream(
            addonName = "AddonA",
            url = "https://example.com/a.m3u8",
            name = "720p"
        )
        val regexMatch = stream(
            addonName = "AddonB",
            url = "https://example.com/b.m3u8",
            name = "2160p Remux"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(nonMatch, regexMatch),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "2160p|Remux",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "unmatched-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(regexMatch, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "   ",
            preferBingeGroupInSelection = true
        )

        assertEquals(first, selected)
    }

    @Test
    fun `manual mode auto-selects matching bingeGroup when prefer enabled`() {
        // Binge-group continuity is intentionally allowed in MANUAL mode so
        // next-episode / resume can skip the picker when a group was locked in.
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(matched, selected)
    }

    @Test
    fun `first stream skips checking and not cached local debrid streams`() {
        val checking = stream(
            addonName = "AddonA",
            name = "Checking",
            infoHash = "abc123",
            cacheState = StreamDebridCacheState.CHECKING
        )
        val notCached = stream(
            addonName = "AddonA",
            name = "Not cached",
            infoHash = "def456",
            cacheState = StreamDebridCacheState.NOT_CACHED
        )
        val unknown = stream(
            addonName = "AddonA",
            name = "Unknown",
            infoHash = "unknown",
            cacheState = StreamDebridCacheState.UNKNOWN
        )
        val cached = stream(
            addonName = "AddonA",
            name = "Cached",
            infoHash = "ghi789",
            cacheState = StreamDebridCacheState.CACHED
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(checking, notCached, unknown, cached),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertEquals(cached, selected)
    }

    @Test
    fun `first stream selects a stream that only has a YouTube id`() {
        // The two streams an addon such as Newsio returns for a YouTube video: the bare ytId,
        // then the watch page as an external link.
        val youTube = stream(addonName = "AddonA", name = "Play video in app", ytId = "dQw4w9WgXcQ")
        val watchPage = stream(
            addonName = "AddonA",
            name = "Open in YouTube app",
            externalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(youTube, watchPage),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
            assertEquals(youTube, selected)
        } else {
            // Without in-app playback the video would open externally, like the watch page.
            assertNull(selected)
        }
    }

    @Test
    fun `first stream skips a blank YouTube id`() {
        val blank = stream(addonName = "AddonA", name = "Blank", ytId = "  ")
        val direct = stream(addonName = "AddonA", url = "https://example.com/direct.m3u8")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(blank, direct),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertEquals(direct, selected)
    }

    @Test
    fun `regex mode selects a matching stream that only has a YouTube id`() {
        val direct = stream(addonName = "AddonA", url = "https://example.com/direct.m3u8", name = "Mirror")
        val youTube = stream(addonName = "AddonA", name = "YouTube", ytId = "dQw4w9WgXcQ")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(direct, youTube),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "youtube",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
            assertEquals(youTube, selected)
        } else {
            assertNull(selected)
        }
    }

    @Test
    fun `orderAddonStreams keeps cached local torrent groups in installed addon order`() {
        val regular = addonStreams(
            "AddonA",
            stream(
                addonName = "AddonA",
                url = "https://example.com/regular.m3u8"
            )
        )
        val cachedDebrid = addonStreams(
            "AddonB",
            stream(
                addonName = "AddonB",
                infoHash = "abc123",
                cacheState = StreamDebridCacheState.CACHED
            )
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(regular, cachedDebrid),
            installedOrder = listOf("AddonA", "AddonB")
        )

        assertEquals(listOf(regular, cachedDebrid), ordered)
    }

    @Test
    fun `NETWORK_QUALITY picks highest quality stream within resolution cap`() {
        val p2160 = stream(
            addonName = "AddonA",
            url = "https://example.com/2160.mp4",
            name = "Movie 2160p UHD"
        )
        val p1080 = stream(
            addonName = "AddonA",
            url = "https://example.com/1080.mp4",
            name = "Movie 1080p FHD"
        )
        val p720 = stream(
            addonName = "AddonA",
            url = "https://example.com/720.mp4",
            name = "Movie 720p HD"
        )

        val selected1080 = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(p2160, p1080, p720),
            mode = StreamAutoPlayMode.NETWORK_QUALITY,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            deviceMaxResolutionPx = 1080
        )
        assertEquals(p1080, selected1080)

        val selected2160 = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(p2160, p1080, p720),
            mode = StreamAutoPlayMode.NETWORK_QUALITY,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            deviceMaxResolutionPx = 2160
        )
        assertEquals(p2160, selected2160)
    }

    @Test
    fun `NETWORK_QUALITY filters stream exceeding bandwidth budget`() {
        val hugeStream = stream(
            addonName = "AddonA",
            url = "https://example.com/huge.mp4",
            name = "Movie 1080p 50GB"
        )
        val smallStream = stream(
            addonName = "AddonA",
            url = "https://example.com/small.mp4",
            name = "Movie 720p 2GB"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(hugeStream, smallStream),
            mode = StreamAutoPlayMode.NETWORK_QUALITY,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            deviceMaxResolutionPx = 1080,
            measuredSpeedMbps = 10.0 // budget is 7.5 Mbps; 50GB requires ~75Mbps, 2GB requires ~3Mbps
        )
        assertEquals(smallStream, selected)
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        name: String? = null,
        bingeGroup: String? = null,
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null,
        ytId: String? = null,
        externalUrl: String? = null
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = ytId,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null
        ),
        addonName = addonName,
        addonLogo = null,
        debridCacheStatus = cacheState?.let {
            StreamDebridCacheStatus(
                providerId = "torbox",
                providerName = "Torbox",
                state = it
            )
        }
    )

    private fun addonStreams(
        addonName: String,
        vararg streams: Stream
    ): AddonStreams = AddonStreams(
        addonName = addonName,
        addonLogo = null,
        streams = streams.toList()
    )
}
