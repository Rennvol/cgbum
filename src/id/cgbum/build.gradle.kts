import io.github.keiyoushi.gradle.api.ContentWarning
plugins { alias(kei.plugins.extension) }
keiyoushi {
    name = "Cgbum"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"
    source {
        lang = "id"
        baseUrl = "https://cgbum.com"
        id = 985734201847562310L
    }
}
