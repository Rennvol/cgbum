import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MagaDaxX"
    versionCode = 1
    libVersion = "1.6"
    contentWarning = ContentWarning.SAFE
    source {
        lang = "id"
        baseUrl = "https://mangadex.org"
        id = 985734201847562311L
    }
}
