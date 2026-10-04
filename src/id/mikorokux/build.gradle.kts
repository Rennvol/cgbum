import io.github.keiyoushi.gradle.api.ContentWarning
plugins { alias(kei.plugins.extension) }
keiyoushi {
    name = "MikoRokuX"
    versionCode = 4
    libVersion = "1.6"
    contentWarning = ContentWarning.NSFW
    source {
        lang = "id"
        baseUrl = "https://mikoroku.com"
        id = 985734201847562312L
    }
}
