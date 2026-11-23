rootProject.name = "idea-compress-plugin"

plugins {
    // Auto-provisions a JDK matching `jvmToolchain(21)` if none is found.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
