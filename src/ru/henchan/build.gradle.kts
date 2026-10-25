import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HenChan"
    versionCode = 104
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "multichan"

    source {
        baseUrl {
            // Все хосты — одна установка, но vhost'ы падают по отдельности, поэтому выбор
            // в настройках полезен. Первый адрес — значение по умолчанию.
            mirrors(
                "hentaichan.live" to "https://hentaichan.live",
                "xxl.hentaichan.live" to "https://xxl.hentaichan.live",
                "x17.h-chan.me" to "https://x17.h-chan.me",
            )
        }
        lang = "ru"
        id = 5504588601186153612L
    }
}
