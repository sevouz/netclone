package com.horis.anikoto

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikototv.to"
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

    private val apiUrl = "https://anikotoapi.site"

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ajax/home/widget/trending?page=" to "Trending",
        "$mainUrl/ajax/home/widget/updated?page=" to "Recently Updated",
        "$mainUrl/filter?page=" to "All Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "${request.data}$page"

        if (request.data.contains("ajax")) {
            // AJAX endpoint returns JSON with HTML
            val response = app.get(url, headers = ajaxHeaders, referer = "$mainUrl/home").text
            val json = parseJson<AjaxResponse>(response)
            if (json.status != 200 || json.result.isNullOrEmpty()) return null

            val document = Jsoup.parse(json.result)
            val items = document.select(".item").mapNotNull { it.toSearchResult() }

            return newHomePageResponse(
                listOf(HomePageList(request.name, items)),
                hasNext = items.size >= 10
            )
        } else {
            // Filter page - also JS rendered, use ajax
            val filterUrl = "$mainUrl/ajax/filter?page=$page"
            val response = app.get(filterUrl, headers = ajaxHeaders, referer = "$mainUrl/filter").text
            val json = parseAjaxResponse(response)
            if (json?.status != 200 || json.result.isNullOrEmpty()) return null

            val document = Jsoup.parse(json.result)
            val items = document.select(".item").mapNotNull { it.toSearchResult() }

            return newHomePageResponse(
                listOf(HomePageList(request.name, items)),
                hasNext = items.size >= 10
            )
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val linkElement = selectFirst(".info .name") ?: selectFirst("a.name") ?: return null
        val title = linkElement.text().trim()
        val href = fixUrl(linkElement.attr("href").substringBefore("/ep-"))
        val posterUrl = selectFirst(".ani.poster img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        } ?: selectFirst("img")?.attr("src")

        val subCount = selectFirst(".ep-status.sub span")?.text()?.trim()?.toIntOrNull()
        val dubCount = selectFirst(".ep-status.dub span")?.text()?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(subCount)
            addDub(dubCount)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/ajax/search/suggest?keyword=$query",
            headers = ajaxHeaders,
            referer = "$mainUrl/home"
        ).text

        val json = parseAjaxResponse(response) ?: return emptyList()
        if (json.result.isNullOrEmpty()) return emptyList()

        val document = Jsoup.parse(json.result)
        return document.select("a").mapNotNull { item ->
            val href = item.attr("href")
            if (href.isNullOrEmpty()) return@mapNotNull null
            val title = item.selectFirst(".srp-detail .film-name")?.text()?.trim()
                ?: item.selectFirst(".info .name")?.text()?.trim()
                ?: item.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            val posterUrl = item.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/ajax/search/suggest?keyword=$query",
            headers = ajaxHeaders,
            referer = "$mainUrl/home"
        ).text

        val json = parseAjaxResponse(response) ?: return emptyList()
        if (json.result.isNullOrEmpty()) return emptyList()

        val document = Jsoup.parse(json.result)
        return document.select("a").mapNotNull { item ->
            val href = item.attr("href")
            if (href.isNullOrEmpty()) return@mapNotNull null
            val title = item.selectFirst(".srp-detail .film-name")?.text()?.trim()
                ?: item.selectFirst(".info .name")?.text()?.trim()
                ?: item.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            val posterUrl = item.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Get anime detail via AJAX
        val slug = url.substringAfterLast("/watch/").substringBefore("/")
        val detailUrl = "$mainUrl/ajax/detail/$slug"

        val response = app.get(detailUrl, headers = ajaxHeaders, referer = url).text
        val json = parseAjaxResponse(response)

        // Try to get the page HTML directly as fallback
        val pageHtml = if (json?.result.isNullOrEmpty()) {
            app.get(url).text
        } else {
            json!!.result!!
        }

        val document = Jsoup.parse(pageHtml)

        val title = document.selectFirst(".anisc-detail .film-name")?.text()?.trim()
            ?: document.selectFirst(".info .name")?.text()?.trim()
            ?: document.selectFirst("h2")?.text()?.trim()
            ?: return null

        val posterUrl = document.selectFirst(".film-poster img")?.attr("src")
            ?: document.selectFirst(".ani.poster img")?.attr("src")

        val description = document.selectFirst(".film-description .text")?.text()?.trim()
            ?: document.selectFirst(".description")?.text()?.trim()

        var type: TvType = TvType.Anime
        val genres = mutableListOf<String>()
        var year: Int? = null
        var status: ShowStatus? = null

        document.select(".anisc-info .item, .info-item").forEach { item ->
            val label = item.selectFirst(".item-head, .label")?.text()?.trim()?.removeSuffix(":") ?: ""
            when {
                label.contains("Type", true) -> {
                    val value = item.selectFirst(".name, .value")?.text() ?: ""
                    type = when {
                        value.contains("Movie", true) -> TvType.AnimeMovie
                        value.contains("OVA", true) || value.contains("ONA", true) -> TvType.OVA
                        else -> TvType.Anime
                    }
                }
                label.contains("Genre", true) -> {
                    item.select("a").forEach { a -> genres.add(a.text().trim()) }
                }
                label.contains("Aired", true) || label.contains("Premiered", true) -> {
                    year = Regex("\\d{4}").find(item.text())?.value?.toIntOrNull()
                }
                label.contains("Status", true) -> {
                    val value = item.text()
                    status = when {
                        value.contains("Airing", true) -> ShowStatus.Ongoing
                        value.contains("Completed", true) || value.contains("Finished", true) -> ShowStatus.Completed
                        else -> null
                    }
                }
            }
        }

        // Get anime ID for episode fetching
        val animeId = document.selectFirst("[data-id]")?.attr("data-id")
            ?: slug.substringAfterLast("-")

        // Fetch episodes
        val episodesResponse = app.get(
            "$mainUrl/ajax/episode/list/$animeId",
            headers = ajaxHeaders,
            referer = url
        ).text

        val epJson = parseAjaxResponse(episodesResponse)
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        if (epJson?.result != null) {
            val epDoc = Jsoup.parse(epJson.result)
            epDoc.select("a[data-id], .ep-item").forEach { ep ->
                val epNum = ep.attr("data-number").toIntOrNull()
                    ?: ep.attr("href").let { Regex("ep-(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                val epTitle = ep.attr("title").ifEmpty { "Episode $epNum" }

                val hasDub = ep.hasClass("dub") || ep.attr("class").contains("dub")
                    || ep.selectFirst(".dub") != null

                // Store animeId and episode number for API lookup
                val episodeData = EpisodeData(
                    animeId = animeId,
                    episodeNum = epNum ?: 1,
                    referer = url
                ).toJson()

                val episode = newEpisode(episodeData) {
                    this.name = epTitle
                    this.episode = epNum
                }

                subEpisodes.add(episode)
                if (hasDub) dubEpisodes.add(episode)
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = posterUrl
            this.plot = description
            this.year = year
            this.tags = genres
            this.showStatus = status
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
        val episodeData = parseJson<EpisodeData>(data)
        val animeId = episodeData.animeId
        val episodeNum = episodeData.episodeNum

        // Fetch episode data from the community API
        val apiResponse = app.get(
            "$apiUrl/series/$animeId",
            headers = mapOf("Referer" to mainUrl)
        ).text

        val seriesData = try {
            parseJson<ApiSeriesResponse>(apiResponse)
        } catch (_: Exception) { return false }

        if (seriesData.ok != true) return false

        // Find the matching episode by number
        val episode = seriesData.data?.episodes?.find { it.number == episodeNum }
            ?: return false

        var foundLinks = false

        // Extract SUB embed URL
        val subUrl = episode.embed_url?.sub
        if (!subUrl.isNullOrEmpty()) {
            try {
                MegaCloudExtractor.extractFromEmbed(
                    embedUrl = subUrl,
                    referer = mainUrl,
                    serverName = "MegaPlay SUB",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                foundLinks = true
            } catch (_: Exception) { }
        }

        // Extract DUB embed URL
        val dubUrl = episode.embed_url?.dub
        if (!dubUrl.isNullOrEmpty()) {
            try {
                MegaCloudExtractor.extractFromEmbed(
                    embedUrl = dubUrl,
                    referer = mainUrl,
                    serverName = "MegaPlay DUB",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                foundLinks = true
            } catch (_: Exception) { }
        }

        return foundLinks
    }

    private fun parseAjaxResponse(text: String): AjaxResponse? {
        return try {
            parseJson<AjaxResponse>(text)
        } catch (_: Exception) { null }
    }

    data class AjaxResponse(
        val status: Int? = null,
        val result: String? = null
    )

    data class EpisodeData(
        val animeId: String,
        val episodeNum: Int,
        val referer: String? = null
    )

    // API response models
    data class ApiSeriesResponse(
        val ok: Boolean? = null,
        val data: ApiSeriesData? = null
    )

    data class ApiSeriesData(
        val anime: ApiAnime? = null,
        val episodes: List<ApiEpisode>? = null
    )

    data class ApiAnime(
        val id: Int? = null,
        val title: String? = null,
        val slug: String? = null
    )

    data class ApiEpisode(
        val id: Int? = null,
        val number: Int? = null,
        val title: String? = null,
        val embed_url: ApiEmbedUrl? = null
    )

    data class ApiEmbedUrl(
        val sub: String? = null,
        val dub: String? = null
    )
}
