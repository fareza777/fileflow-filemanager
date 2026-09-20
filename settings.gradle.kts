// Repository URLs are declared explicitly rather than through google() /
// mavenCentral() because this machine rewrites those well-known URLs to
// mirrors via ~/.gradle/init.d/mirror.gradle, and the Google mirror is
// currently answering 502 for some artifacts — which Gradle treats as a hard
// failure instead of falling through to the next repository. These two
// endpoints are the canonical Google Maven and the Google-hosted Maven Central
// mirror, and neither URL is rewritten by that init script.
pluginManagement {
    repositories {
        maven {
            name = "GoogleMaven"
            url = uri("https://maven.google.com")
        }
        maven {
            name = "MavenCentral"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "GoogleMaven"
            url = uri("https://maven.google.com")
        }
        maven {
            name = "MavenCentral"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
    }
}

rootProject.name = "FileFlow"
include(":app")
