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
                val epId = ep.attr("data-id").ifEmpty { ep.attr("href").substringAfterLast("ep=") }
                val epNum = ep.attr("data-number").toIntOrNull()
                    ?: ep.attr("href").let { Regex("ep-(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                val epTitle = ep.attr("title").ifEmpty { "Episode $epNum" }

                val hasDub = ep.hasClass("dub") || ep.attr("class").contains("dub")
                    || ep.selectFirst(".dub") != null

                val episodeData = EpisodeData(epId, url).toJson()
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
        val episodeId = episodeData.id

        // Get servers
        val serversResponse = app.get(
            "$mainUrl/ajax/episode/servers?episodeId=$episodeId",
            headers = ajaxHeaders,
            referer = episodeData.referer ?: mainUrl
        ).text

        val serversJson = parseAjaxResponse(serversResponse) ?: return false
        if (serversJson.result.isNullOrEmpty()) return false

        val serversDoc = Jsoup.parse(serversJson.result)

        val serverTypes = listOf("sub", "dub", "raw")
        serverTypes.forEach { type ->
            serversDoc.select(".servers-$type .server-item, .server-item[data-type=$type]").forEach { server ->
                val serverId = server.attr("data-id")
                val serverName = server.text().trim()

                if (serverId.isNotEmpty()) {
                    try {
                        val sourceResponse = app.get(
                            "$mainUrl/ajax/episode/sources?id=$serverId",
                            headers = ajaxHeaders,
                            referer = episodeData.referer ?: mainUrl
                        ).text

                        val sourceData = parseSourceResponse(sourceResponse)
                        val embedUrl = sourceData?.link ?: return@forEach

                        val prefix = when (type) {
                            "dub" -> "[DUB] "
                            "raw" -> "[RAW] "
                            else -> ""
                        }

                        MegaCloudExtractor.extractFromEmbed(
                            embedUrl = embedUrl,
                            referer = mainUrl,
                            serverName = "$prefix$serverName",
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    } catch (_: Exception) { }
                }
            }
        }

        return true
    }

    private fun parseAjaxResponse(text: String): AjaxResponse? {
        return try {
            parseJson<AjaxResponse>(text)
        } catch (_: Exception) { null }
    }

    private fun parseSourceResponse(text: String): SourceResponse? {
        return try {
            parseJson<SourceResponse>(text)
        } catch (_: Exception) { null }
    }

    data class AjaxResponse(
        val status: Int? = null,
        val result: String? = null
    )

    data class EpisodeData(
        val id: String,
        val referer: String? = null
    )

    data class SourceResponse(
        val link: String? = null,
        val type: String? = null,
        val server: String? = null
    )
}
