package com.cncverse

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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

        val servers = if (isMovie) {
            listOf(
                "Flux" to "https://movish.net/moviebox-embed/movie/$tmdbId",
                "Shadow" to "https://vidlink.pro/movie/$tmdbId",
                "Cine" to "https://cinesrc.st/embed/movie/$tmdbId",
                "Stream" to "https://rivestream.org/embed?type=movie&id=$tmdbId",
                "Torrent" to "https://rivestream.org/embed/torrent?type=movie&id=$tmdbId",
                "Crown" to "https://www.vidking.net/embed/movie/$tmdbId",
                "Quantum" to "https://player.videasy.net/movie/$tmdbId",
                "Prism" to "https://vidsrc.me/embed/movie/$tmdbId",
                "Onyx" to "https://2embed.cc/embed/$tmdbId",
                "Titan" to "https://multiembed.mov/?video_id=$tmdbId",
                "Vortex" to "https://vidsrcme.ru/embed/movie?tmdb=$tmdbId",
            )
        } else {
            listOf(
                "Flux" to "https://movish.net/moviebox-embed/tv/$tmdbId/$season/$episode",
                "Shadow" to "https://vidlink.pro/tv/$tmdbId/$season/$episode",
                "Cine" to "https://cinesrc.st/embed/tv/$tmdbId/$season/$episode",
                "Stream" to "https://rivestream.org/embed?type=tv&id=$tmdbId&season=$season&episode=$episode",
                "Torrent" to "https://rivestream.org/embed/torrent?type=tv&id=$tmdbId&season=$season&episode=$episode",
                "Crown" to "https://www.vidking.net/embed/tv/$tmdbId/$season/$episode",
                "Quantum" to "https://player.videasy.net/tv/$tmdbId/$season/$episode",
                "Prism" to "https://vidsrc.me/embed/tv/$tmdbId/$season/$episode",
                "Onyx" to "https://2embed.cc/embedtv/$tmdbId&s=$season&e=$episode",
                "Titan" to "https://multiembed.mov/?video_id=$tmdbId&s=$season&e=$episode",
                "Vortex" to "https://vidsrcme.ru/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode",
            )
        }

        servers.amap { (serverName, embedUrl) ->
            try {
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            } catch (_: Exception) {
                // If no extractor found, try to load as iframe source
                try {
                    val doc = app.get(embedUrl, referer = mainUrl).document
                    val iframeSrc = doc.selectFirst("iframe")?.attr("src")
                    if (!iframeSrc.isNullOrBlank()) {
                        loadExtractor(iframeSrc, embedUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) { }
            }
        }

        return true
    }

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
