import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MultiManga"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        baseUrl {
            mirrors(
                "multi-manga.com" to "https://multi-manga.com",
                "w1.multi-manga.com" to "https://w1.multi-manga.com",
                "w2.multi-manga.com" to "https://w2.multi-manga.com",
                "w3.multi-manga.com" to "https://w3.multi-manga.com",
                "w4.multi-manga.com" to "https://w4.multi-manga.com",
                "w5.multi-manga.com" to "https://w5.multi-manga.com",
                "w6.multi-manga.com" to "https://w6.multi-manga.com",
                "ww1.multi-manga.com" to "https://ww1.multi-manga.com",
                "ww.multi-manga.com" to "https://ww.multi-manga.com",
            )
        }
        lang = "ru"
        id = 3420183351909474560L
    }
}
