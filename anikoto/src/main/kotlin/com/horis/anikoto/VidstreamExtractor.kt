package com.horis.anikoto

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile

class VidstreamExtractor : ExtractorApi() {
    override val name = "Vidstream"
    override val mainUrl = "https://vidstream.pro"
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

        extractSourcesFromScript(scriptContent, url, subtitleCallback, callback)

        // Check for iframe redirects
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        if (!iframeSrc.isNullOrEmpty()) {
            val iframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc"
            else if (iframeSrc.startsWith("/")) "$mainUrl$iframeSrc"
            else iframeSrc

            val iframeResponse = app.get(iframeUrl, referer = url)
            val iframeScript = iframeResponse.document.select("script").map { it.data() }.joinToString("\n")
            extractSourcesFromScript(iframeScript, iframeUrl, subtitleCallback, callback)
        }
    }

    private suspend fun extractSourcesFromScript(
        scriptContent: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val m3u8Regex = Regex("""(?:file|source|src|url)\s*[:=]\s*['"]([^'"]*\.m3u8[^'"]*)['"]""")
        m3u8Regex.findAll(scriptContent).forEach { match ->
            callback.invoke(
                newExtractorLink(name, name, match.groupValues[1], type = ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        val mp4Regex = Regex("""(?:file|source|src|url)\s*[:=]\s*['"]([^'"]*\.mp4[^'"]*)['"]""")
        mp4Regex.findAll(scriptContent).forEach { match ->
            callback.invoke(
                newExtractorLink(name, name, match.groupValues[1], type = ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\)""", RegexOption.DOT_MATCHES_ALL)
        packedRegex.findAll(scriptContent).forEach { packedMatch ->
            val unpacked = JsUnpacker.unpack(packedMatch.value) ?: return@forEach
            val unpackedM3u8 = Regex("""['"]([^'"]*\.m3u8[^'"]*)['"]""").find(unpacked)
            if (unpackedM3u8 != null) {
                callback.invoke(
                    newExtractorLink(name, name, unpackedM3u8.groupValues[1], type = ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Subtitles
        val trackRegex = Regex("""\{[^}]*?['"]?file['"]?\s*:\s*['"]([^'"]+\.(vtt|srt|ass))[^}]*?['"]?label['"]?\s*:\s*['"]([^'"]+)['"][^}]*}""")
        trackRegex.findAll(scriptContent).forEach { match ->
            subtitleCallback.invoke(SubtitleFile(match.groupValues[3], match.groupValues[1]))
        }
        val trackRegex2 = Regex("""\{[^}]*?['"]?label['"]?\s*:\s*['"]([^'"]+)['"][^}]*?['"]?file['"]?\s*:\s*['"]([^'"]+\.(vtt|srt|ass))['"][^}]*}""")
        trackRegex2.findAll(scriptContent).forEach { match ->
            subtitleCallback.invoke(SubtitleFile(match.groupValues[1], match.groupValues[2]))
        }
    }
}
