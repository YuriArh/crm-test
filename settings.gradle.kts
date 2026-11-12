rootProject.name = "exchange"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include("dispatch")
include("e2e")
// include("carrier-emulator") — added when the emulator is implemented
