package com.nuvio.tv.core.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

object StreamAutoPlaySelector {
    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>,
        preferredNames: Set<String> = emptySet()
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams
        if (preferredNames.isNotEmpty()) {
            val (preferred, rest) = streams.partition { it.addonName in preferredNames }
            return preferred + orderAddonStreams(rest, installedOrder)
        }

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = streams.partition {
            it.streams.any { stream -> stream.isDirectDebrid() }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries
        val (addonEntries, pluginEntries) = remainingEntries.partition { it.addonName in addonRankByName }
        val orderedAddons = addonEntries.sortedBy { addonRankByName.getValue(it.addonName) }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    private fun isPlayable(stream: Stream): Boolean {
        // External URL streams (e.g. error pages, web links) are not playable.
        if (stream.isExternal()) return false
        if (stream.serverTarget != null) return true
        // Streams with a direct URL are always playable regardless of debrid cache status.
        if (stream.getStreamUrl() != null) return true
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.NOT_CACHED,
            StreamDebridCacheState.UNKNOWN -> return false
            StreamDebridCacheState.CACHED,
            null -> Unit
        }
        return stream.isTorrent() ||
            stream.isDirectDebrid() ||
            // A YouTube id is resolved to a playable URL on the device when the stream is played.
            // Builds without in-app YouTube playback open the watch page instead, an external link.
            (AppFeaturePolicy.inAppTrailerPlaybackEnabled && stream.youTubeIdToResolve() != null)
    }



    fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        deviceMaxResolutionPx: Int? = null,
        measuredSpeedMbps: Double? = null
    ): Stream? {
        if (streams.isEmpty()) return null

        val effectiveSource = if (!AppFeaturePolicy.pluginsEnabled && source == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY
        } else {
            source
        }

        val sourceScopedStreams = when (effectiveSource) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.serverTarget != null || it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (stream.serverTarget != null) {
                true
            } else if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return null

        // Binge group matching takes priority over mode and source filters — even in MANUAL mode,
        // a persisted binge group should auto-play without showing the picker.
        // Search ALL streams, not just candidateStreams, since binge group reuse should
        // not be limited by autoplay source/addon selection filters.
        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            val bingeGroupMatch = streams.firstOrNull { stream ->
                !stream.isExternal() &&
                    stream.behaviorHints?.bingeGroup == targetBingeGroup &&
                    isPlayable(stream)
            }
            if (bingeGroupMatch != null) return bingeGroupMatch
            // When bingeGroupOnly is set (MANUAL mode with only binge-group
            // preference enabled), don't fall back to a non-matching stream —
            // return null so the caller shows the stream picker instead.
            if (bingeGroupOnly) return null
        }

        if (bingeGroupOnly) return null

        if (mode == StreamAutoPlayMode.MANUAL) return null

        return when (mode) {
            StreamAutoPlayMode.MANUAL -> null
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams.firstOrNull { isPlayable(it) }
            StreamAutoPlayMode.NETWORK_QUALITY -> {
                rankByNetworkQuality(
                    candidates = candidateStreams,
                    deviceMaxResolutionPx = deviceMaxResolutionPx,
                    measuredSpeedMbps = measuredSpeedMbps
                )
            }
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()
 
                // Try to compile the user regex
                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                if (userRegex == null) return null

                // Auto-extract exclusion patterns from negative lookaheads
                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex("\\b(${exclusionWords.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
                } else null

                // 1. Build list of ALL regex‑matching streams
                val matchingStreams = candidateStreams.filter { stream ->
                    if (!isPlayable(stream)) return@filter false

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.title.orEmpty()).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(stream.getStreamUrl().orEmpty())
                        if (stream.isTorrent()) append(' ').append(stream.infoHash.orEmpty())
                    }

                    // Must match include pattern
                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    // Must NOT match exclusion pattern
                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }

                if (matchingStreams.isEmpty()) return null
                matchingStreams.firstOrNull { isPlayable(it) }
            }
        }
    }

    fun rankByNetworkQuality(
        candidates: List<Stream>,
        deviceMaxResolutionPx: Int? = null,
        measuredSpeedMbps: Double? = null
    ): Stream? {
        val playable = candidates.filter { isPlayable(it) }
        if (playable.isEmpty()) return null

        val budgetMbps = measuredSpeedMbps?.let { it * 0.75 }
        val defaultSettings = DebridSettings()

        val parsed = playable.map { stream ->
            val facts = DirectDebridStreamFilter.facts(stream, defaultSettings)
            val bitrateMbps = streamEstimatedBitrateMbps(stream, facts.size)
            Triple(stream, facts, bitrateMbps)
        }

        // Filter streams that exceed bandwidth budget (if known)
        val withinBandwidth = if (budgetMbps != null) {
            val matching = parsed.filter { (_, _, bitrate) ->
                bitrate == null || bitrate <= budgetMbps
            }
            if (matching.isNotEmpty()) matching else parsed
        } else {
            parsed
        }

        // Filter streams that exceed device display resolution (if cap provided or detected)
        val effectiveCap = deviceMaxResolutionPx ?: runCatching {
            val metrics = android.content.res.Resources.getSystem().displayMetrics
            val minDim = kotlin.math.min(metrics.widthPixels, metrics.heightPixels)
            if (minDim > 0) minDim else null
        }.getOrNull()

        val withinResolution = if (effectiveCap != null && effectiveCap > 0) {
            val matching = withinBandwidth.filter { (_, facts, _) ->
                facts.resolution.value == 0 || facts.resolution.value <= effectiveCap
            }
            if (matching.isNotEmpty()) matching else withinBandwidth
        } else {
            withinBandwidth
        }

        return withinResolution
            .sortedWith(
                compareByDescending<Triple<Stream, DirectDebridStreamFilter.StreamFacts, Double?>> { (_, facts, _) -> facts.resolution.value }
                    .thenByDescending { (_, facts, _) -> qualityWeight(facts.quality) }
                    .thenByDescending { (_, facts, _) -> facts.size ?: 0L }
            )
            .firstOrNull()
            ?.first
    }

    private fun qualityWeight(quality: DebridStreamQuality): Int = when (quality) {
        DebridStreamQuality.BLURAY_REMUX -> 100
        DebridStreamQuality.BLURAY -> 90
        DebridStreamQuality.WEB_DL -> 80
        DebridStreamQuality.WEBRIP -> 70
        DebridStreamQuality.HDRIP -> 60
        DebridStreamQuality.HD_RIP -> 50
        DebridStreamQuality.DVDRIP -> 40
        DebridStreamQuality.HDTV -> 30
        DebridStreamQuality.TC, DebridStreamQuality.TS -> 20
        DebridStreamQuality.CAM, DebridStreamQuality.SCR -> 10
        DebridStreamQuality.UNKNOWN -> 0
    }

    fun streamEstimatedBitrateMbps(stream: Stream, sizeBytes: Long? = null): Double? {
        val bytes = sizeBytes ?: parseSizeFromText(stream) ?: return null
        val durationSec = 5400.0 // 90 min default baseline
        return (bytes * 8.0) / (durationSec * 1_000_000.0)
    }

    fun parseSizeFromText(stream: Stream): Long? {
        val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()} ${stream.description.orEmpty()}"
        val regex = Regex("""(?i)(\d+(?:\.\d+)?)\s*(GB|MB|GiB|MiB)""")
        val match = regex.find(text) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase()
        val bytesPerUnit = when {
            unit.startsWith("G") -> 1024L * 1024L * 1024L
            unit.startsWith("M") -> 1024L * 1024L
            else -> 1024L
        }
        return (number * bytesPerUnit).toLong()
    }
}
