rootProject.name = "morphe-fdroid-server"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // morphe-patcher itself (app.morphe:morphe-patcher) - published to GitHub
        // Packages, which requires an authenticated request even for public
        // packages. Any valid GitHub token works for read access (it does not
        // need to belong to the MorpheApp org) - provide it via the
        // `gpr.user`/`gpr.key` Gradle properties, or the `GITHUB_ACTOR`/
        // `GITHUB_TOKEN` env vars (see README.md "Build and Run" / the
        // Dockerfile for how the Docker build passes these in). Scoped to just
        // this group so credential resolution/failures don't affect any other
        // dependency lookup.
        maven {
            name = "MorphePackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/morphe-patcher")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .getOrElse("")
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .getOrElse("")
            }
        }
        // Obtain baksmali/smali from source builds - https://github.com/iBotPeaches/smali
        // Remove when official smali releases come out again.
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
    }
}

include(":app")
