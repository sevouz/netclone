package com.cncverse

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class Anikoto : MainAPI() {
    override var mainUrl = "https://anikototv.to"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    companion object {
        private const val TAG = "Anikoto"
    }

    override val mainPage = mainPageOf(
        "recently-updated" to "Recently Updated",
        "top-airing" to "Top Airing",
        "most-popular" to "Most Popular",
        "most-favorite" to "Most Favorite",
        "completed" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}?page=$page"
        val document = app.get(url).document
        val home = document.select("div.flw-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.film-name a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("h3.film-name a")?.attr("href") ?: return null)
        val posterUrl = this.selectFirst("img.film-poster-img")?.attr("data-src")
            ?: this.selectFirst("img.film-poster-img")?.attr("src")

        val subCount = this.selectFirst("div.tick-sub")?.text()?.toIntOrNull()
        val dubCount = this.selectFirst("div.tick-dub")?.text()?.toIntOrNull()
        val epsCount = this.selectFirst("div.tick-eps")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(
                dubExist = dubCount != null,
                subExist = subCount != null,
                dubEpisodes = dubCount,
                subEpisodes = subCount ?: epsCount
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        val document = app.get(url).document
        return document.select("div.flw-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h2.film-name")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        val poster = document.selectFirst("img.film-poster-img")?.attr("src")
        val description = document.selectFirst("div.film-description div.text")?.text()?.trim()

        val animeId = document.selectFirst("div[data-id]")?.attr("data-id")
            ?: url.substringAfterLast("-").substringBefore("/")

        // Extract info items
        val infoItems = document.select("div.anisc-info div.item")
        var year: Int? = null
        var status: ShowStatus? = null
        val tags = mutableListOf<String>()
        var japName: String? = null
        var malId: Int? = null
        var aniListId: Int? = null

        infoItems.forEach { item ->
            val label = item.selectFirst("span.item-head")?.text()?.trim()?.removeSuffix(":")?.trim()
            when (label) {
                "Aired" -> {
                    year = Regex("(\\d{4})").find(item.text())?.groupValues?.get(1)?.toIntOrNull()
                }
                "Status" -> {
                    val statusText = item.selectFirst("span.name")?.text()?.trim()
                    status = when {
                        statusText?.contains("Airing", true) == true -> ShowStatus.Ongoing
                        statusText?.contains("Finished", true) == true -> ShowStatus.Completed
                        else -> null
                    }
                }
                "Genres" -> {
                    tags.addAll(item.select("a").map { it.text().trim() })
                }
                "Japanese" -> {
                    japName = item.selectFirst("span.name")?.text()?.trim()
                }
                "MAL Score" -> {
                    // Could extract MAL score if needed
                }
            }
        }

        // Try to get MAL/AniList IDs from external links
        document.select("a.btn-play").forEach { link ->
            val href = link.attr("href")
            if (href.contains("myanimelist.net")) {
                malId = href.substringAfterLast("/").toIntOrNull()
            } else if (href.contains("anilist.co")) {
                aniListId = href.substringAfterLast("/").toIntOrNull()
            }
        }

        // Get episodes via AJAX
        val episodesUrl = "$mainUrl/ajax/v2/episode/list/$animeId"
        val episodesHtml = app.get(episodesUrl, headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url
        )).parsedSafe<AjaxResponse>()?.html ?: ""

        val episodesDoc = org.jsoup.Jsoup.parse(episodesHtml)
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        episodesDoc.select("a.ep-item").forEach { ep ->
            val epNum = ep.attr("data-number").toIntOrNull()
            val epTitle = ep.attr("title").ifBlank { "Episode $epNum" }
            val epId = ep.attr("data-id")
            val epHref = ep.attr("href").ifBlank { "$url/ep-$epNum" }
            val isFiller = ep.hasClass("ssl-item-filler")

            // Data format: animeId|episodeId
            val dataStr = "$animeId|$epId"

            val episode = newEpisode(dataStr) {
                this.name = epTitle
                this.episode = epNum
            }

            subEpisodes.add(episode)
        }

        val tvType = if (subEpisodes.size <= 1 && url.contains("movie")) TvType.AnimeMovie else TvType.Anime

        return newAnimeLoadResponse(title, url, tvType) {
            engName = title
            japName = japName
            this.posterUrl = poster
            this.year = year
            this.showStatus = status
            this.plot = description
            this.tags = tags.ifEmpty { null }
            addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false

        val animeId = parts[0]
        val episodeId = parts[1]

        // Get available servers for this episode
        val serversUrl = "$mainUrl/ajax/v2/episode/servers?episodeId=$episodeId"
        val serversHtml = app.get(serversUrl, headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to mainUrl
        )).parsedSafe<AjaxResponse>()?.html ?: return false

        val serversDoc = org.jsoup.Jsoup.parse(serversHtml)

        // Process each server
        serversDoc.select("div.server-item").forEach { server ->
            val serverId = server.attr("data-id")
            val serverName = server.text().trim()
            val serverType = server.attr("data-type") // sub or dub

            try {
                // Get the embed URL for this server
                val sourceUrl = "$mainUrl/ajax/v2/episode/sources?id=$serverId"
                val sourceResponse = app.get(sourceUrl, headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl
                )).text

                val sourceData = parseJson<SourceResponse>(sourceResponse)
                val embedUrl = sourceData.link
                if (embedUrl.isNullOrBlank()) return@forEach

                Log.d(TAG, "Server: $serverName ($serverType), Embed: $embedUrl")

                // Route to appropriate extractor based on the embed URL
                when {
                    embedUrl.contains("megaplay.buzz") || embedUrl.contains("megacloud") -> {
                        MegaPlayExtractor().getUrl(
                            embedUrl,
                            mainUrl,
                            subtitleCallback,
                            callback
                        )
                    }
                    embedUrl.contains("rapid-cloud") -> {
                        MegaPlayExtractor().getUrl(
                            embedUrl,
                            mainUrl,
                            subtitleCallback,
                            callback
                        )
                    }
                    else -> {
                        loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading server $serverName: ${e.message}")
            }
        }

        return true
    }

    data class AjaxResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("html") val html: String? = null,
    )

    data class SourceResponse(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("sources") val sources: List<Any>? = null,
        @JsonProperty("tracks") val tracks: List<Any>? = null,
        @JsonProperty("htmlGuide") val htmlGuide: String? = null,
    )
}
