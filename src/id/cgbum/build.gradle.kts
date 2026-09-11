import io.github.keiyoushi.gradle.api.ContentWarning
plugins { alias(kei.plugins.extension) }
keiyoushi {
    name = "Cgbum"
    versionCode = 1
    libVersion = "1.6"
    contentWarning = ContentWarning.SAFE
    source {
        lang = "id"
        baseUrl = "https://cgbum.com"
        id = 985734201847562310L
    }
}
