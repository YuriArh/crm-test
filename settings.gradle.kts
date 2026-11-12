rootProject.name = "exchange"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include("backend")
// include("emulator") — added when the emulator is implemented
