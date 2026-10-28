// Use an integer for version numbers
version = 1

cloudstream {
    description = "AnimeOn.cc anime provider (Russian)"
    authors = listOf("voith")
    language = "ru"

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Anime")
    iconUrl = "https://animeon.cc/favicon-192.png"

    isCrossPlatform = true
}
