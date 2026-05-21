package com.cncverse

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class MovishProvider : MainAPI() {
    override var mainUrl = "https://movish.net"
    override var name = "Movish"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val apiKey = "98ae14df2b8d8f8f8136499daf79f0e0"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?api_key=$apiKey&page=" to "Trending",
        "$tmdbAPI/movie/popular?api_key=$apiKey&page=" to "Popular Movies",
        "$tmdbAPI/tv/popular?api_key=$apiKey&page=" to "Popular TV Shows",
        "$tmdbAPI/movie/top_rated?api_key=$apiKey&page=" to "Top Rated Movies",
        "$tmdbAPI/tv/top_rated?api_key=$apiKey&page=" to "Top Rated TV Shows",
        "$tmdbAPI/movie/now_playing?api_key=$apiKey&page=" to "Now Playing",
        "$tmdbAPI/tv/airing_today?api_key=$apiKey&page=" to "Airing Today",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val response = app.get(url).parsed<TmdbResponse>()
        val home = response.results.mapNotNull { media ->
            val title = media.title ?: media.name ?: return@mapNotNull null
            val id = media.id ?: return@mapNotNull null
            val type = if (media.mediaType == "tv" || request.data.contains("/tv/")) TvType.TvSeries else TvType.Movie
            val posterUrl = media.posterPath?.let { "$imageBase$it" }

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, "$id|tv", type) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, "$id|movie", type) {
                    this.posterUrl = posterUrl
                }
            }
        }
        return newHomePageResponse(HomePageList(request.name, home))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$tmdbAPI/search/multi?api_key=$apiKey&query=$query"
        val response = app.get(url).parsed<TmdbResponse>()
        return response.results.mapNotNull { media ->
            val title = media.title ?: media.name ?: return@mapNotNull null
            val id = media.id ?: return@mapNotNull null
            if (media.mediaType == "person") return@mapNotNull null
            val type = if (media.mediaType == "tv") TvType.TvSeries else TvType.Movie
            val posterUrl = media.posterPath?.let { "$imageBase$it" }

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, "$id|tv", type) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, "$id|movie", type) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // Cloudstream may prepend mainUrl, so strip any URL prefix to get "id|type"
        val cleanUrl = url.replace("$mainUrl/", "").replace(mainUrl, "")
        val parts = cleanUrl.split("|")
        val idStr = parts[0]
        val mediaType = parts.getOrElse(1) { "movie" }
        val tmdbId = idStr.toInt()

        if (mediaType == "tv") {
            val details = app.get("$tmdbAPI/tv/$tmdbId?api_key=$apiKey&append_to_response=credits,videos").parsed<TmdbTvDetails>()
            val title = details.name ?: "Unknown"
            val posterUrl = details.posterPath?.let { "$imageBase$it" }
            val backgroundUrl = details.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
            val year = details.firstAirDate?.take(4)?.toIntOrNull()
            val tags = details.genres?.map { it.name } ?: emptyList()
            val rating = details.voteAverage?.toString()
            val description = details.overview
            val actors = details.credits?.cast?.map {
                ActorData(Actor(it.name ?: "", it.profilePath?.let { p -> "$imageBase$p" }), roleString = it.character)
            } ?: emptyList()

            val trailer = details.videos?.results?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" }?.key?.let {
                "https://www.youtube.com/watch?v=$it"
            }

            val episodes = mutableListOf<Episode>()
            details.seasons?.filter { it.seasonNumber != null && it.seasonNumber > 0 }?.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                val seasonDetails = app.get("$tmdbAPI/tv/$tmdbId/season/$seasonNum?api_key=$apiKey").parsed<TmdbSeasonDetails>()
                seasonDetails.episodes?.forEach { ep ->
                    val epNum = ep.episodeNumber ?: return@forEach
                    episodes.add(
                        newEpisode("$tmdbId|tv|$seasonNum|$epNum") {
                            this.name = ep.name
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = ep.stillPath?.let { "$imageBase$it" }
                            this.description = ep.overview
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                addTrailer(trailer)
            }
        } else {
            val details = app.get("$tmdbAPI/movie/$tmdbId?api_key=$apiKey&append_to_response=credits,videos").parsed<TmdbMovieDetails>()
            val title = details.title ?: "Unknown"
            val posterUrl = details.posterPath?.let { "$imageBase$it" }
            val backgroundUrl = details.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
            val year = details.releaseDate?.take(4)?.toIntOrNull()
            val tags = details.genres?.map { it.name } ?: emptyList()
            val rating = details.voteAverage?.toString()
            val description = details.overview
            val duration = details.runtime
            val actors = details.credits?.cast?.map {
                ActorData(Actor(it.name ?: "", it.profilePath?.let { p -> "$imageBase$p" }), roleString = it.character)
            } ?: emptyList()

            val trailer = details.videos?.results?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" }?.key?.let {
                "https://www.youtube.com/watch?v=$it"
            }

            return newMovieLoadResponse(title, url, TvType.Movie, "$tmdbId|movie|0|0") {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                this.actors = actors
                addTrailer(trailer)
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
        val tmdbId = parts[0]
        val mediaType = parts[1]
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val isMovie = mediaType == "movie"

        // Get title and year from TMDB for Videasy API
        val type = if (isMovie) "movie" else "tv"
        val tmdbDetails = try {
            app.get("$tmdbAPI/$type/$tmdbId?api_key=$apiKey&append_to_response=external_ids").parsed<TmdbDetailsForLinks>()
        } catch (_: Exception) { null }

        val title = tmdbDetails?.title ?: tmdbDetails?.name ?: ""
        val year = (tmdbDetails?.releaseDate ?: tmdbDetails?.firstAirDate ?: "").take(4)
        val imdbId = tmdbDetails?.externalIds?.imdbId ?: ""

        // --- Videasy Extraction (10 servers) ---
        val videasyServers = mapOf(
            "Neon" to "https://api.videasy.net/myflixerzupcloud/sources-with-title",
            "Cypher" to "https://api.videasy.net/moviebox/sources-with-title",
            "Reyna" to "https://api.videasy.net/primewire/sources-with-title",
            "Omen" to "https://api.videasy.net/onionplay/sources-with-title",
            "Breach" to "https://api.videasy.net/m4uhd/sources-with-title",
            "Ghost" to "https://api.videasy.net/primesrcme/sources-with-title",
            "Sage" to "https://api.videasy.net/1movies/sources-with-title",
            "Vyse" to "https://api.videasy.net/hdmovie/sources-with-title",
            "Raze" to "https://api.videasy.net/superflix/sources-with-title",
        )

        val videasyHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://player.videasy.net",
            "Referer" to "https://player.videasy.net/"
        )

        if (title.isNotBlank()) {
            videasyServers.entries.toList().amap { (serverName, serverUrl) ->
                try {
                    var apiUrl = "$serverUrl?title=${URLEncoder.encode(title, "UTF-8")}" +
                            "&mediaType=$type&year=$year" +
                            "&tmdbId=$tmdbId&imdbId=$imdbId"
                    if (!isMovie) apiUrl += "&seasonId=$season&episodeId=$episode"

                    val encResponse = app.get(apiUrl, headers = videasyHeaders).text
                    if (encResponse.length < 20 || encResponse.startsWith("<")) return@amap

                    // Decrypt via enc-dec.app
                    val decResponse = app.post(
                        "https://enc-dec.app/api/dec-videasy",
                        json = mapOf("text" to encResponse, "id" to tmdbId),
                        headers = mapOf("Content-Type" to "application/json")
                    ).parsed<VideasyDecryptResponse>()

                    val sources = decResponse.result?.sources ?: decResponse.sources ?: return@amap
                    sources.forEach { source ->
                        val url = source.url ?: return@forEach
                        callback.invoke(
                            newExtractorLink(
                                source = "Videasy $serverName",
                                name = "Videasy $serverName - ${source.quality ?: "Auto"}",
                                url = url,
                                type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                            ) {
                                this.referer = "https://player.videasy.net/"
                                this.quality = getQualityInt(source.quality)
                            }
                        )
                    }
                } catch (_: Exception) { }
            }

            // Yoru server (movies only)
            if (isMovie) {
                try {
                    var apiUrl = "https://api.videasy.net/cdn/sources-with-title?title=${URLEncoder.encode(title, "UTF-8")}" +
                            "&mediaType=$type&year=$year" +
                            "&tmdbId=$tmdbId&imdbId=$imdbId"

                    val encResponse = app.get(apiUrl, headers = videasyHeaders).text
                    if (encResponse.length >= 20 && !encResponse.startsWith("<")) {
                        val decResponse = app.post(
                            "https://enc-dec.app/api/dec-videasy",
                            json = mapOf("text" to encResponse, "id" to tmdbId),
                            headers = mapOf("Content-Type" to "application/json")
                        ).parsed<VideasyDecryptResponse>()

                        val sources = decResponse.result?.sources ?: decResponse.sources
                        sources?.forEach { source ->
                            val url = source.url ?: return@forEach
                            callback.invoke(
                                newExtractorLink(
                                    source = "Videasy Yoru",
                                    name = "Videasy Yoru - ${source.quality ?: "Auto"}",
                                    url = url,
                                    type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                                ) {
                                    this.referer = "https://player.videasy.net/"
                                    this.quality = getQualityInt(source.quality)
                                }
                            )
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        // --- Vidlink Extraction ---
        try {
            val encRes = app.get(
                "https://enc-dec.app/api/enc-vidlink?text=${URLEncoder.encode(tmdbId, "UTF-8")}",
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            ).parsed<EncDecResponse>()

            val encodedTmdb = encRes.result
            if (!encodedTmdb.isNullOrBlank()) {
                val vidlinkUrl = if (isMovie) {
                    "https://vidlink.pro/api/b/movie/$encodedTmdb?multiLang=0"
                } else {
                    "https://vidlink.pro/api/b/tv/$encodedTmdb/$season/$episode?multiLang=0"
                }

                val vidlinkRes = app.get(
                    vidlinkUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer" to "https://vidlink.pro"
                    )
                ).parsed<VidlinkResponse>()

                val playlist = vidlinkRes.stream?.playlist
                if (!playlist.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "Vidlink",
                            name = "Vidlink",
                            url = playlist,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://vidlink.pro"
                        }
                    )
                }
            }
        } catch (_: Exception) { }

        return true
    }

    private fun getQualityInt(quality: String?): Int {
        return when {
            quality == null -> Qualities.Unknown.value
            quality.contains("2160") || quality.contains("4K", true) -> Qualities.P2160.value
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // --- Vidlink/Videasy Data Classes ---

    data class EncDecResponse(
        @JsonProperty("result") val result: String? = null
    )

    data class VidlinkResponse(
        @JsonProperty("stream") val stream: VidlinkStream? = null
    )

    data class VidlinkStream(
        @JsonProperty("playlist") val playlist: String? = null
    )

    data class VideasyDecryptResponse(
        @JsonProperty("result") val result: VideasyResult? = null,
        @JsonProperty("sources") val sources: List<VideasySource>? = null
    )

    data class VideasyResult(
        @JsonProperty("sources") val sources: List<VideasySource>? = null
    )

    data class VideasySource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    data class TmdbDetailsForLinks(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null
    )

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null
    )

    // --- TMDB Data Classes ---

    data class TmdbResponse(
        @JsonProperty("results") val results: List<TmdbMedia> = emptyList()
    )

    data class TmdbMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class TmdbMovieDetails(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("videos") val videos: TmdbVideos? = null,
    )

    data class TmdbTvDetails(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("videos") val videos: TmdbVideos? = null,
    )

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class TmdbSeasonDetails(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null,
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
    )

    data class TmdbGenre(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String = "",
    )

    data class TmdbCredits(
        @JsonProperty("cast") val cast: List<TmdbCast>? = null,
    )

    data class TmdbCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class TmdbVideos(
        @JsonProperty("results") val results: List<TmdbVideo>? = null,
    )

    data class TmdbVideo(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("type") val type: String? = null,
    )
}
