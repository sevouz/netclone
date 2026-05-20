package com.horis.anikoto

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikoto.me"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/recently-updated" to "Recently Updated",
        "$mainUrl/top-airing" to "Top Airing",
        "$mainUrl/most-popular" to "Most Popular",
        "$mainUrl/new-release" to "New Release"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document

        val items = when (request.name) {
            "Home" -> {
                val homeItems = mutableListOf<HomePageList>()

                // Recently Updated section
                val recentlyUpdated = document.select(".film_list-wrap .flw-item").mapNotNull {
                    it.toSearchResult()
                }
                if (recentlyUpdated.isNotEmpty()) {
                    homeItems.add(HomePageList("Recently Updated", recentlyUpdated))
                }

                // Top anime section
                val topAnime = document.select(".anif-block-ul li").mapNotNull {
                    it.toTopResult()
                }
                if (topAnime.isNotEmpty()) {
                    homeItems.add(HomePageList("Top Anime", topAnime))
                }

                return newHomePageResponse(homeItems, hasNext = false)
            }
            else -> {
                document.select(".film_list-wrap .flw-item").mapNotNull {
                    it.toSearchResult()
                }
            }
        }

        val hasNextPage = document.selectFirst(".pagination .page-item.active + .page-item a") != null

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = hasNextPage
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val filmDetail = selectFirst(".film-detail") ?: return null
        val nameElement = filmDetail.selectFirst(".film-name a") ?: return null
        val title = nameElement.text().trim()
        val href = fixUrl(nameElement.attr("href"))
        val posterUrl = selectFirst(".film-poster img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        val subCount = selectFirst(".tick-sub")?.text()?.toIntOrNull()
        val dubCount = selectFirst(".tick-dub")?.text()?.toIntOrNull()
        val episodeCount = selectFirst(".tick-eps")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(subCount)
            addDub(dubCount)
        }
    }

    private fun Element.toTopResult(): AnimeSearchResponse? {
        val nameElement = selectFirst(".film-name a") ?: return null
        val title = nameElement.text().trim()
        val href = fixUrl(nameElement.attr("href"))
        val posterUrl = selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/ajax/search/suggest?keyword=$query",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to mainUrl
            )
        ).document

        return response.select(".nav-item").mapNotNull { item ->
            val href = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.selectFirst(".srp-detail .film-name")?.text()?.trim() ?: return@mapNotNull null
            val posterUrl = item.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/filter/?keyword=$query").document

        return document.select(".film_list-wrap .flw-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".anisc-detail .film-name")?.text()?.trim() ?: return null
        val posterUrl = document.selectFirst(".film-poster img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        val description = document.selectFirst(".film-description .text")?.text()?.trim()

        val animeInfo = document.select(".anisc-info .item")
        var type: TvType = TvType.Anime
        var status: ShowStatus? = null
        var duration: Int? = null
        val genres = mutableListOf<String>()
        var year: Int? = null
        var malId: Int? = null
        var aniListId: Int? = null

        animeInfo.forEach { item ->
            val label = item.selectFirst(".item-head")?.text()?.trim()?.removeSuffix(":") ?: ""
            val value = item.selectFirst(".name")?.text()?.trim()
                ?: item.ownText().trim()

            when {
                label.contains("Type", true) -> {
                    type = when {
                        value.contains("Movie", true) -> TvType.AnimeMovie
                        value.contains("OVA", true) -> TvType.OVA
                        value.contains("ONA", true) -> TvType.OVA
                        value.contains("Special", true) -> TvType.OVA
                        else -> TvType.Anime
                    }
                }
                label.contains("Status", true) -> {
                    status = when {
                        value.contains("Currently Airing", true) -> ShowStatus.Ongoing
                        value.contains("Finished", true) || value.contains("Completed", true) -> ShowStatus.Completed
                        else -> null
                    }
                }
                label.contains("Genre", true) -> {
                    item.select("a").forEach { a -> genres.add(a.text().trim()) }
                }
                label.contains("Aired", true) || label.contains("Premiered", true) -> {
                    year = Regex("\\d{4}").find(value)?.value?.toIntOrNull()
                }
                label.contains("Duration", true) -> {
                    duration = Regex("(\\d+)\\s*min").find(value)?.groupValues?.get(1)?.toIntOrNull()
                }
                label.contains("MAL", true) -> {
                    malId = Regex("(\\d+)").find(value)?.value?.toIntOrNull()
                }
            }
        }

        // Extract MAL/AniList IDs from page links
        document.select("a[href*=myanimelist.net/anime/]").firstOrNull()?.let {
            malId = Regex("anime/(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }
        document.select("a[href*=anilist.co/anime/]").firstOrNull()?.let {
            aniListId = Regex("anime/(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }

        // Get the anime ID from the URL for episode fetching
        val animeId = document.selectFirst("#watch-main")?.attr("data-id")
            ?: url.substringAfterLast("/").substringAfterLast("-")

        // Fetch episodes
        val episodesHtml = app.get(
            "$mainUrl/ajax/episode/list/$animeId",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url
            )
        ).document

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        episodesHtml.select(".ep-item").forEach { ep ->
            val epNum = ep.attr("data-number").toIntOrNull()
            val epTitle = ep.attr("title").ifEmpty { "Episode $epNum" }
            val epId = ep.attr("data-id")
            val isFiller = ep.hasClass("ssl-item-filler")

            val episodeData = EpisodeData(epId, url).toJson()

            val episode = newEpisode(episodeData) {
                this.name = epTitle
                this.episode = epNum
                if (isFiller) this.description = "[Filler]"
            }

            // Check if sub/dub available
            val hasSub = ep.attr("data-ids").contains("sub") || ep.selectFirst(".ssli-sub") != null
            val hasDub = ep.attr("data-ids").contains("dub") || ep.selectFirst(".ssli-dub") != null

            subEpisodes.add(episode)
            if (hasDub) dubEpisodes.add(episode)
        }

        // If no episodes found from AJAX, try parsing from the page directly
        if (subEpisodes.isEmpty()) {
            document.select(".ss-list a").forEach { ep ->
                val epHref = ep.attr("href")
                val epNum = ep.attr("data-number")?.toIntOrNull()
                    ?: Regex("ep-(\\d+)").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val epTitle = ep.attr("title").ifEmpty { "Episode $epNum" }
                val epId = ep.attr("data-id").ifEmpty {
                    epHref.substringAfterLast("?ep=").ifEmpty { epHref }
                }

                val episodeData = EpisodeData(epId, fixUrl(epHref)).toJson()
                subEpisodes.add(
                    newEpisode(episodeData) {
                        this.name = epTitle
                        this.episode = epNum
                    }
                )
            }
        }

        val recommendations = document.select(".film_list-wrap .flw-item").mapNotNull {
            it.toSearchResult()
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = posterUrl
            this.plot = description
            this.year = year
            this.tags = genres
            this.showStatus = status
            this.duration = duration
            this.recommendations = recommendations
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
        val episodeData = parseJson<EpisodeData>(data)
        val episodeId = episodeData.id

        // Get available servers for this episode
        val serversHtml = app.get(
            "$mainUrl/ajax/episode/servers?episodeId=$episodeId",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to (episodeData.referer ?: mainUrl)
            )
        ).document

        // Parse server items - try both sub and dub
        val serverTypes = listOf("sub", "dub", "raw")

        serverTypes.forEach { type ->
            serversHtml.select(".servers-$type .server-item, .server-item[data-type=$type]").forEach { server ->
                val serverId = server.attr("data-id")
                val serverName = server.text().trim()

                if (serverId.isNotEmpty()) {
                    try {
                        val sourceResponse = app.get(
                            "$mainUrl/ajax/episode/sources?id=$serverId",
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to (episodeData.referer ?: mainUrl)
                            )
                        ).text

                        val sourceData = parseSourceResponse(sourceResponse)
                        val embedUrl = sourceData?.link ?: return@forEach

                        val prefix = when (type) {
                            "dub" -> "[DUB] "
                            "raw" -> "[RAW] "
                            else -> ""
                        }

                        // Try MegaCloud extractor first for known hosts
                        val isMegaCloud = listOf(
                            "megacloud", "rapid-cloud", "rabbitstream",
                            "vidstream", "vidcloud", "megaplay"
                        ).any { embedUrl.contains(it, true) }

                        if (isMegaCloud) {
                            MegaCloudExtractor.extractFromEmbed(
                                embedUrl = embedUrl,
                                referer = mainUrl,
                                serverName = "$prefix$serverName",
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        } else {
                            // Fallback to generic extractor
                            loadExtractor(
                                url = embedUrl,
                                referer = mainUrl,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        }
                    } catch (e: Exception) {
                        // Skip failed servers
                    }
                }
            }
        }

        return true
    }

    companion object {
        fun parseSourceResponse(text: String): SourceResponse? {
            return try {
                AppUtils.parseJson<SourceResponse>(text)
            } catch (e: Exception) {
                null
            }
        }
    }

    data class EpisodeData(
        val id: String,
        val referer: String? = null
    )

    data class SourceResponse(
        val link: String? = null,
        val type: String? = null,
        val server: String? = null,
        val tracks: List<TrackData>? = null
    )

    data class TrackData(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null
    )
}
