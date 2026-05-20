package com.horis.anikoto

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.SubtitleFile

/**
 * MegaCloud/RapidCloud extractor for HiAnime-style sites.
 */
class MegaCloudExtractor {

    companion object {
        suspend fun extractFromEmbed(
            embedUrl: String,
            referer: String,
            serverName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            try {
                val response = app.get(embedUrl, referer = referer)
                val document = response.document
                val scriptContent = document.select("script").map { it.data() }.joinToString("\n")

                // Method 1: Direct source URLs
                val m3u8Regex = Regex("""(?:file|source|src|url)\s*[:=]\s*['"]([^'"]*\.m3u8[^'"]*)['"]""")
                m3u8Regex.findAll(scriptContent).forEach { match ->
                    callback.invoke(
                        newExtractorLink(serverName, serverName, match.groupValues[1], type = ExtractorLinkType.M3U8) {
                            this.referer = embedUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }

                // Method 2: AJAX sources endpoint
                val videoId = Regex("""(?:embed|e|v)/([^?/]+)""").find(embedUrl)?.groupValues?.get(1)
                    ?: embedUrl.substringAfterLast("/").substringBefore("?")

                if (videoId.isNotEmpty()) {
                    val host = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return

                    val sourceEndpoints = listOf(
                        "https://$host/ajax/embed-6/getSources?id=$videoId",
                        "https://$host/ajax/embed-6-v2/getSources?id=$videoId",
                        "https://$host/ajax/embed/getSources?id=$videoId",
                        "https://$host/ajax/v2/embed/getSources?id=$videoId"
                    )

                    for (endpoint in sourceEndpoints) {
                        try {
                            val sourceResponse = app.get(
                                endpoint,
                                headers = mapOf(
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Referer" to embedUrl
                                )
                            ).text

                            val sourceData = try {
                                parseJson<MegaCloudSourceResponse>(sourceResponse)
                            } catch (_: Exception) { continue }

                            sourceData.sources?.forEach { source ->
                                val sourceUrl = source.file ?: return@forEach
                                val linkType = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                callback.invoke(
                                    newExtractorLink(serverName, serverName, sourceUrl, type = linkType) {
                                        this.referer = embedUrl
                                        this.quality = getQualityFromLabel(source.label)
                                    }
                                )
                            }

                            sourceData.tracks?.forEach { track ->
                                if (track.kind == "captions" || track.kind == "subtitles") {
                                    val trackFile = track.file ?: return@forEach
                                    val trackLabel = track.label ?: "Unknown"
                                    subtitleCallback.invoke(SubtitleFile(trackLabel, trackFile))
                                }
                            }

                            if (sourceData.sources?.isNotEmpty() == true) return
                        } catch (_: Exception) { continue }
                    }
                }

                // Method 3: Iframe redirect
                val iframeSrc = document.selectFirst("iframe")?.attr("src")
                if (!iframeSrc.isNullOrEmpty()) {
                    val iframeUrl = when {
                        iframeSrc.startsWith("//") -> "https:$iframeSrc"
                        iframeSrc.startsWith("/") -> {
                            val baseHost = Regex("""https?://[^/]+""").find(embedUrl)?.value ?: return
                            "$baseHost$iframeSrc"
                        }
                        else -> iframeSrc
                    }
                    extractFromEmbed(iframeUrl, embedUrl, serverName, subtitleCallback, callback)
                }

            } catch (_: Exception) { }
        }

        private fun getQualityFromLabel(label: String?): Int {
            if (label == null) return Qualities.Unknown.value
            return when {
                label.contains("4K", true) || label.contains("2160", true) -> Qualities.P2160.value
                label.contains("1080", true) -> Qualities.P1080.value
                label.contains("720", true) -> Qualities.P720.value
                label.contains("480", true) -> Qualities.P480.value
                label.contains("360", true) -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
        }
    }

    data class MegaCloudSourceResponse(
        val sources: List<MegaCloudSource>? = null,
        val sourcesBackup: List<MegaCloudSource>? = null,
        val tracks: List<MegaCloudTrack>? = null,
        val encrypted: Boolean? = null
    )

    data class MegaCloudSource(
        val file: String? = null,
        val type: String? = null,
        val label: String? = null
    )

    data class MegaCloudTrack(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null,
        val default: Boolean? = null
    )
}
