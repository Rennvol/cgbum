import io.github.keiyoushi.gradle.api.ContentWarning
plugins { alias(kei.plugins.extension) }
keiyoushi {
    name = "MikoRokuX"
    versionCode = 6
    libVersion = "1.6"
    contentWarning = ContentWarning.NSFW
    source {
        lang = "id"
        baseUrl = "https://www.mikodrive.my.id"
        id = 985734201847562312L
    }
}
