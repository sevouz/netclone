package com.cncverse

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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
        private const val API_URL = "https://anikotoapi.site"
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
        // Remove /ep-X from the URL to get the base watch URL
        val cleanHref = href.replace(Regex("/ep-\\d+$"), "")

        val posterUrl = this.selectFirst("div.ani.poster img")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        val subText = this.selectFirst("span.ep-status.sub span")?.text()?.trim()
        val dubText = this.selectFirst("span.ep-status.dub span")?.text()?.trim()

        val subCount = subText?.toIntOrNull()
        val dubCount = dubText?.toIntOrNull()

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
        // Extract slug from URL like /watch/re-zero-starting-life-in-another-world-season-4-4hk9h
        val slug = url.substringAfterLast("/watch/").substringBefore("/").substringBefore("?")

        // Try to find the anime in the API
        val apiAnime = findAnimeInApi(slug)

        if (apiAnime != null) {
            return loadFromApi(apiAnime, url)
        }

        // Fallback: use recent-anime API list
        return loadFallback(url, slug)
    }

    private suspend fun findAnimeInApi(slug: String): ApiAnime? {
        return try {
            val recentResponse = app.get("$API_URL/recent-anime").text
            val recentResult = parseJson<ApiRecentResponse>(recentResponse)
            recentResult.data.firstOrNull { it.slug == slug }
        } catch (e: Exception) {
            Log.e(TAG, "API lookup failed: ${e.message}")
            null
        }
    }

    private suspend fun loadFromApi(anime: ApiAnime, url: String): LoadResponse {
        // Get full series data with episodes
        val seriesResponse = app.get("$API_URL/series/${anime.id}").text
        val seriesResult = parseJson<ApiSeriesResponse>(seriesResponse)
        val seriesData = seriesResult.data

        val title = seriesData.anime.title
        val poster = seriesData.anime.poster
        val description = seriesData.anime.description
        val year = seriesData.anime.year
        val malId = seriesData.anime.malId?.toIntOrNull()
        val aniId = seriesData.anime.aniId?.toIntOrNull()
        val backgroundImage = seriesData.anime.backgroundImage?.ifBlank { null }

        val status = when {
            seriesData.anime.status?.contains("Airing", true) == true -> ShowStatus.Ongoing
            seriesData.anime.status?.contains("Finished", true) == true -> ShowStatus.Completed
            else -> null
        }

        val genres = seriesData.anime.termsByType?.genre ?: emptyList()

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        seriesData.episodes.forEach { ep ->
            val subUrl = ep.embedUrl?.sub
            val dubUrl = ep.embedUrl?.dub

            if (!subUrl.isNullOrBlank()) {
                subEpisodes.add(newEpisode(subUrl) {
                    this.name = ep.title ?: "Episode ${ep.number}"
                    this.episode = ep.number
                })
            }

            if (!dubUrl.isNullOrBlank()) {
                dubEpisodes.add(newEpisode(dubUrl) {
                    this.name = ep.title ?: "Episode ${ep.number}"
                    this.episode = ep.number
                })
            }
        }

        val tvType = if (subEpisodes.size <= 1 &&
            seriesData.anime.termsByType?.type?.any { it.contains("Movie", true) } == true
        ) TvType.AnimeMovie else TvType.Anime

        return newAnimeLoadResponse(title, url, tvType) {
            engName = title
            japName = seriesData.anime.native
            this.posterUrl = poster
            this.backgroundPosterUrl = backgroundImage ?: poster
            this.year = year
            this.showStatus = status
            this.plot = description
            this.tags = genres.ifEmpty { null }
            addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
            addMalId(malId)
            addAniListId(aniId)
        }
    }

    private suspend fun loadFallback(url: String, slug: String): LoadResponse {
        // Scrape the watch page for basic info
        val document = app.get(url).document

        val title = document.selectFirst("h2.film-name")?.text()?.trim()
            ?: document.selectFirst("a.name.d-title")?.text()?.trim()
            ?: slug.replace("-", " ").split(" ").dropLast(1)
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        val poster = document.selectFirst("div.ani.poster img")?.attr("src")
            ?: document.selectFirst("img.film-poster-img")?.attr("src")

        val description = document.selectFirst("div.film-description")?.text()?.trim()
            ?: document.selectFirst("div.description")?.text()?.trim()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, emptyList())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is the megaplay.buzz embed URL directly from the API
        // e.g. https://megaplay.buzz/stream/s-2/169591/sub
        Log.d(TAG, "Loading links from: $data")

        if (data.contains("megaplay.buzz")) {
            MegaPlayExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
            return true
        }

        // Fallback: try generic extractor
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }

    // API Data Classes
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiRecentResponse(
        @JsonProperty("ok") val ok: Boolean,
        @JsonProperty("data") val data: List<ApiAnime>,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiSeriesResponse(
        @JsonProperty("ok") val ok: Boolean,
        @JsonProperty("data") val data: ApiSeriesData,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiSeriesData(
        @JsonProperty("anime") val anime: ApiAnime,
        @JsonProperty("episodes") val episodes: List<ApiEpisode>,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiAnime(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String,
        @JsonProperty("alternative") val alternative: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("aired") val aired: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("score") val score: String? = null,
        @JsonProperty("mal_id") val malId: String? = null,
        @JsonProperty("ani_id") val aniId: String? = null,
        @JsonProperty("episodes") val episodes: String? = null,
        @JsonProperty("is_sub") val isSub: Int? = null,
        @JsonProperty("background_image") val backgroundImage: String? = null,
        @JsonProperty("terms_by_type") val termsByType: ApiTerms? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiTerms(
        @JsonProperty("genre") val genre: List<String>? = null,
        @JsonProperty("type") val type: List<String>? = null,
        @JsonProperty("studios") val studios: List<String>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiEpisode(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("number") val number: Int,
        @JsonProperty("episode_embed_id") val episodeEmbedId: String? = null,
        @JsonProperty("embed_url") val embedUrl: ApiEmbedUrl? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiEmbedUrl(
        @JsonProperty("sub") val sub: String? = null,
        @JsonProperty("dub") val dub: String? = null,
    )
}
