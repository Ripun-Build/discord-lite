rootProject.name = "discord-lite-server"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Signal publishes libsignal here, not on Maven Central.
        maven("https://build-artifacts.signal.org/libraries/maven/") {
            content { includeGroup("org.signal") }
        }
    }
}
