
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import java.nio.charset.StandardCharsets
import java.util.Base64

fun resolveSigningKeyMaterial(rawValue: String?): String? {
    val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.contains("-----BEGIN PGP")) {
        return value
    }

    return try {
        val decoded = String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8).trim()
        if (decoded.contains("-----BEGIN PGP")) decoded else value
    } catch (_: IllegalArgumentException) {
        value
    }
}

val gdalVersion = providers.gradleProperty("gdalVersion")
    .orElse("3.9.0")
    .get()
val gdal4kVersion = providers.gradleProperty("gdal4kVersion")
    .orElse("1.0.1-SNAPSHOT")
    .get()
val gdalPackageVersion = "$gdalVersion-$gdal4kVersion"
val requestedPublishTarget = providers.gradleProperty("publishTarget").orElse("mavenCentral").get()
val publishTargetParam = if (requestedPublishTarget == "ossrh") "mavenCentral" else requestedPublishTarget
val githubPackagesUrl = providers.gradleProperty("githubPackagesUrl").orNull
    ?: providers.environmentVariable("GITHUB_PACKAGES_URL").orNull
    ?: providers.environmentVariable("GITHUB_REPOSITORY").orNull?.let { "https://maven.pkg.github.com/$it" }
val githubPackagesUsername = providers.gradleProperty("githubPackagesUsername").orNull
    ?: providers.environmentVariable("GITHUB_ACTOR").orNull
val githubPackagesPassword = providers.gradleProperty("githubPackagesPassword").orNull
    ?: providers.environmentVariable("GITHUB_TOKEN").orNull
val mavenCentralUsername = providers.gradleProperty("mavenCentralUsername").orNull
    ?: providers.gradleProperty("sonatypeUsername").orNull
    ?: providers.gradleProperty("ossrh.username").orNull
    ?: providers.environmentVariable("MAVEN_CENTRAL_USERNAME").orNull
    ?: providers.environmentVariable("SONATYPE_USERNAME").orNull
    ?: providers.environmentVariable("OSSRH_USERNAME").orNull
val mavenCentralPassword = providers.gradleProperty("mavenCentralPassword").orNull
    ?: providers.gradleProperty("sonatypePassword").orNull
    ?: providers.gradleProperty("ossrh.password").orNull
    ?: providers.environmentVariable("MAVEN_CENTRAL_PASSWORD").orNull
    ?: providers.environmentVariable("SONATYPE_PASSWORD").orNull
    ?: providers.environmentVariable("OSSRH_PASSWORD").orNull
val signingKey = resolveSigningKeyMaterial(
    providers.gradleProperty("signingKey").orNull
        ?: providers.environmentVariable("SIGNING_KEY").orNull,
)
val signingKeyId = providers.gradleProperty("signingKeyId").orNull
    ?: providers.environmentVariable("SIGNING_KEY_ID").orNull
val signingPassword = providers.gradleProperty("signingPassword").orNull
    ?: providers.environmentVariable("SIGNING_PASSWORD").orNull
    ?: ""
val mavenCentralReleasesUrl = "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
val mavenCentralSnapshotsUrl = "https://central.sonatype.com/repository/maven-snapshots/"
val publishTasksRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.startsWith("publish") && !taskName.endsWith("ToMavenLocal")
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
}

allprojects {
    group = "dev.tronto.gdal4k"
    version = gdalPackageVersion
}

subprojects {
    plugins.withId("maven-publish") {
        pluginManager.apply("signing")

        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url.set("https://github.com/tronto20/gdal-bundle")
                    scm {
                        connection.set("scm:git:git://github.com/tronto20/gdal-bundle.git")
                        developerConnection.set("scm:git:ssh://git@github.com/tronto20/gdal-bundle.git")
                        url.set("https://github.com/tronto20/gdal-bundle")
                    }
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("tronto20")
                            name.set("tronto20")
                        }
                    }
                }
            }

            repositories {
                when (publishTargetParam) {
                    "githubPackages" -> maven {
                        name = "GitHubPackages"
                        url = uri(githubPackagesUrl ?: "https://maven.pkg.github.com/invalid/invalid")
                        credentials {
                            username = githubPackagesUsername
                            password = githubPackagesPassword
                        }
                    }
                    "mavenCentral" -> maven {
                        name = "MavenCentral"
                        url = uri(if (version.toString().endsWith("SNAPSHOT")) mavenCentralSnapshotsUrl else mavenCentralReleasesUrl)
                        credentials {
                            username = mavenCentralUsername
                            password = mavenCentralPassword
                        }
                    }
                    else -> throw GradleException("The 'publishTarget' property must be either 'mavenCentral' or 'githubPackages'")
                }
            }
        }

        extensions.configure<SigningExtension> {
            setRequired {
                publishTargetParam == "mavenCentral" && gradle.taskGraph.allTasks.any { task ->
                    task.name.startsWith("publish") && !task.name.endsWith("ToMavenLocal")
                }
            }

            if (publishTargetParam == "mavenCentral" && publishTasksRequested) {
                useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
                val publishing = extensions.getByType(PublishingExtension::class.java)
                sign(publishing.publications)
            }
        }
    }
}
