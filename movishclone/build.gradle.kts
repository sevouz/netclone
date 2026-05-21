// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "en"

    description = "Watch Movies & TV Shows from Movish.net with multiple embed servers"
    authors = listOf("sevouz")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://movish.net/static/img/Movish.net.png"
}
