
import org.gradle.api.publish.PublishingExtension

val gdalPackageVersion = providers.gradleProperty("gdalVersion")
    .orElse(providers.gradleProperty("gdal4kVersion"))
    .orElse("3.9.0")
    .get()

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
}

allprojects {
    group = "dev.gdal4k"
    version = gdalPackageVersion
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            val githubPackagesUrl = providers.environmentVariable("GITHUB_PACKAGES_URL").orNull
                ?: providers.environmentVariable("GITHUB_REPOSITORY").orNull?.let { "https://maven.pkg.github.com/$it" }
            if (!githubPackagesUrl.isNullOrBlank()) {
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri(githubPackagesUrl)
                        credentials {
                            username = providers.environmentVariable("GITHUB_ACTOR").orNull
                            password = providers.environmentVariable("GITHUB_TOKEN").orNull
                        }
                    }
                }
            }
        }
    }
}
