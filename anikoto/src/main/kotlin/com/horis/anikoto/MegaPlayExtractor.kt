package com.horis.anikoto

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile

class MegaPlayExtractor : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val document = response.document
        val scriptContent = document.select("script").map { it.data() }.joinToString("\n")

        // Pattern 1: Direct m3u8 URL in script
        val m3u8Regex = Regex("""(?:file|source|src)\s*[:=]\s*['"]([^'"]*\.m3u8[^'"]*)['"]""")
        val m3u8Match = m3u8Regex.find(scriptContent)

        if (m3u8Match != null) {
            callback.invoke(
                newExtractorLink(name, name, m3u8Match.groupValues[1], type = ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // Pattern 2: Packed JS
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\)""", RegexOption.DOT_MATCHES_ALL)
        val packedMatch = packedRegex.find(scriptContent)
        if (packedMatch != null) {
            val unpacked = JsUnpacker.unpack(packedMatch.value)
            val unpackedM3u8 = m3u8Regex.find(unpacked ?: "")
            if (unpackedM3u8 != null) {
                callback.invoke(
                    newExtractorLink(name, name, unpackedM3u8.groupValues[1], type = ExtractorLinkType.M3U8) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Pattern 3: Sources array
        val sourcesRegex = Regex("""sources\s*[:=]\s*\[([^\]]+)]""")
        val sourcesMatch = sourcesRegex.find(scriptContent)
        if (sourcesMatch != null) {
            val sourcesBlock = sourcesMatch.groupValues[1]
            val fileRegex = Regex("""['"]?file['"]?\s*:\s*['"]([^'"]+)['"]""")
            fileRegex.findAll(sourcesBlock).forEach { match ->
                val fileUrl = match.groupValues[1]
                val linkType = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(name, name, fileUrl, type = linkType) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Extract subtitles
        val trackRegex = Regex("""tracks\s*[:=]\s*\[([^\]]+)]""")
        val trackMatch = trackRegex.find(scriptContent)
        if (trackMatch != null) {
            val tracksBlock = trackMatch.groupValues[1]
            val subtitleRegex = Regex("""\{[^}]*?['"]?file['"]?\s*:\s*['"]([^'"]+\.vtt[^'"]*)['"][^}]*?['"]?label['"]?\s*:\s*['"]([^'"]+)['"][^}]*}""")
            subtitleRegex.findAll(tracksBlock).forEach { match ->
                subtitleCallback.invoke(SubtitleFile(match.groupValues[2], match.groupValues[1]))
            }
        }
    }
}
