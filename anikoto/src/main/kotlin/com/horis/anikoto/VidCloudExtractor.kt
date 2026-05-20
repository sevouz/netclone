package com.horis.anikoto

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile

class VidCloudExtractor : ExtractorApi() {
    override val name = "VidCloud"
    override val mainUrl = "https://vidcloud9.com"
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

        val m3u8Regex = Regex("""(?:file|source|src|url)\s*[:=]\s*['"]([^'"]*\.m3u8[^'"]*)['"]""")
        m3u8Regex.findAll(scriptContent).forEach { match ->
            callback.invoke(
                newExtractorLink(name, name, match.groupValues[1], type = ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // Sources array
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

        // Packed JS
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\)""", RegexOption.DOT_MATCHES_ALL)
        packedRegex.findAll(scriptContent).forEach { packedMatch ->
            val unpacked = JsUnpacker.unpack(packedMatch.value) ?: return@forEach
            m3u8Regex.findAll(unpacked).forEach { match ->
                callback.invoke(
                    newExtractorLink(name, name, match.groupValues[1], type = ExtractorLinkType.M3U8) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Iframe
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        if (!iframeSrc.isNullOrEmpty()) {
            val iframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc"
            else if (iframeSrc.startsWith("/")) "$mainUrl$iframeSrc"
            else iframeSrc

            try {
                val iframeResponse = app.get(iframeUrl, referer = url)
                val iframeScript = iframeResponse.document.select("script").map { it.data() }.joinToString("\n")
                m3u8Regex.findAll(iframeScript).forEach { match ->
                    callback.invoke(
                        newExtractorLink(name, name, match.groupValues[1], type = ExtractorLinkType.M3U8) {
                            this.referer = iframeUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } catch (_: Exception) { }
        }

        // Subtitles
        val trackRegex = Regex("""\{[^}]*?['"]?file['"]?\s*:\s*['"]([^'"]+\.(vtt|srt|ass))[^'"]*['"][^}]*?['"]?label['"]?\s*:\s*['"]([^'"]+)['"][^}]*}""")
        trackRegex.findAll(scriptContent).forEach { match ->
            subtitleCallback.invoke(SubtitleFile(match.groupValues[3], match.groupValues[1]))
        }
    }
}
