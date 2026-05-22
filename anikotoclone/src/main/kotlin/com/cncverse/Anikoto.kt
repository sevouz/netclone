package com.cncverse

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Anikoto : MainAPI() {
    override var mainUrl = "https://anikoto.cz"
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
        "$mainUrl/filter?page=" to "Latest",
        "$mainUrl/status/ongoing?page=" to "Ongoing",
        "$mainUrl/most-viewed?page=" to "Most Viewed",
        "$mainUrl/status/completed?page=" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val document = app.get(url).document
        val home = document.select("div#list-items div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val nameTag = this.selectFirst("a.name.d-title") ?: return null
        val title = nameTag.text().trim().ifBlank { return null }
        val href = fixUrl(nameTag.attr("href"))
        val cleanHref = href.replace(Regex("/ep-\\d+$"), "")

        val posterUrl = this.selectFirst("div.ani.poster img")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        val subCount = this.selectFirst("span.ep-status.sub span")?.text()?.trim()?.toIntOrNull()
        val dubCount = this.selectFirst("span.ep-status.dub span")?.text()?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, cleanHref, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(
                dubExist = dubCount != null && dubCount > 0,
                subExist = subCount != null && subCount > 0,
                dubEpisodes = dubCount,
                subEpisodes = subCount
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=$query"
        val document = app.get(url).document
        return document.select("div#list-items div.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val watchMain = document.selectFirst("div#watch-main")
        val animeId = watchMain?.attr("data-id") ?: ""

        val title = document.selectFirst("h2.film-name a")?.text()?.trim()
            ?: document.selectFirst("h2.film-name")?.text()?.trim()
            ?: "Unknown"

        val poster = document.selectFirst("div.anisc-poster img")?.attr("src")
            ?: document.selectFirst("img.film-poster-img")?.attr("src")

        val description = document.selectFirst("div.film-description div.text")?.text()?.trim()

        // Get episodes via AJAX
        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url
        )

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        if (animeId.isNotBlank()) {
            try {
                val epResponse = app.get(
                    "$mainUrl/ajax/episode/list/$animeId",
                    headers = ajaxHeaders
                ).text
                val epResult = parseJson<AjaxResponse>(epResponse)
                val epDoc = Jsoup.parse(epResult.result ?: "")

                epDoc.select("ul.ep-range li a").forEach { epLink ->
                    val epNum = epLink.attr("data-num").toIntOrNull() ?: return@forEach
                    val epTitle = epLink.selectFirst("span.d-title")?.text() ?: "Episode $epNum"
                    val dataIds = epLink.attr("data-ids")
                    val hasSub = epLink.attr("data-sub") == "1"
                    val hasDub = epLink.attr("data-dub") == "1"

                    // Store: animeId|dataIds|epNum as episode data
                    val episodeData = "$animeId|$dataIds|$epNum"

                    if (hasSub) {
                        subEpisodes.add(newEpisode(episodeData) {
                            this.name = epTitle
                            this.episode = epNum
                        })
                    }
                    if (hasDub) {
                        dubEpisodes.add(newEpisode("$episodeData|dub") {
                            this.name = epTitle
                            this.episode = epNum
                        })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load episodes: ${e.message}")
            }
        }

        // Extract metadata
        var year: Int? = null
        var malId: Int? = null
        var aniListId: Int? = null
        var status: ShowStatus? = null
        val tags = mutableListOf<String>()

        document.select("div.anisc-info div.item").forEach { item ->
            val label = item.selectFirst("span.item-head")?.text()?.trim()?.removeSuffix(":")
            when {
                label?.contains("Aired") == true -> {
                    year = Regex("(\\d{4})").find(item.text())?.groupValues?.get(1)?.toIntOrNull()
                }
                label?.contains("Status") == true -> {
                    val statusText = item.selectFirst("span.name")?.text()
                    status = when {
                        statusText?.contains("Airing", true) == true -> ShowStatus.Ongoing
                        statusText?.contains("Finished", true) == true -> ShowStatus.Completed
                        else -> null
                    }
                }
                label?.contains("Genre") == true -> {
                    tags.addAll(item.select("a").map { it.text().trim() })
                }
            }
        }

        document.select("a[href*=myanimelist.net]").firstOrNull()?.let {
            malId = it.attr("href").substringAfterLast("/").toIntOrNull()
        }
        document.select("a[href*=anilist.co]").firstOrNull()?.let {
            aniListId = it.attr("href").substringAfterLast("/").toIntOrNull()
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            this.posterUrl = poster
            this.year = year
            this.showStatus = status
            this.plot = description
            this.tags = tags.ifEmpty { null }
            addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
            addMalId(malId)
            addAniListId(aniListId)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: animeId|dataIds|epNum or animeId|dataIds|epNum|dub
        val parts = data.split("|")
        if (parts.size < 3) return false

        val dataIds = parts[1]
        val isDub = parts.size > 3 && parts[3] == "dub"

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to mainUrl
        )

        // Step 1: Get server list
        val serverResponse = app.get(
            "$mainUrl/ajax/server/list?servers=$dataIds",
            headers = ajaxHeaders
        ).text
        val serverResult = parseJson<AjaxResponse>(serverResponse)
        val serverDoc = Jsoup.parse(serverResult.result ?: return false)

        // Step 2: For each server, get the embed URL
        val targetType = if (isDub) "dub" else "sub"
        val serverItems = serverDoc.select("div.type[data-type=$targetType] li[data-link-id]")

        if (serverItems.isEmpty()) {
            // Try all servers if specific type not found
            serverDoc.select("li[data-link-id]")
        }

        serverItems.forEach { server ->
            val linkId = server.attr("data-link-id")
            val serverName = server.text().trim()

            if (linkId.isBlank()) return@forEach

            try {
                // Step 3: Get actual embed URL
                val embedResponse = app.get(
                    "$mainUrl/ajax/server?get=$linkId",
                    headers = ajaxHeaders
                ).text
                val embedResult = parseJson<ServerGetResponse>(embedResponse)
                val embedUrl = embedResult.result?.url ?: return@forEach

                Log.d(TAG, "Server: $serverName, Embed: $embedUrl")

                // Step 4: Extract video from megaplay.buzz
                if (embedUrl.contains("megaplay.buzz")) {
                    MegaPlayExtractor().getUrl(embedUrl, "$mainUrl/", subtitleCallback, callback)
                } else {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error with server $serverName: ${e.message}")
            }
        }

        return true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AjaxResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerGetResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: ServerResult? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerResult(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("skip_data") val skipData: Any? = null,
    )
}
