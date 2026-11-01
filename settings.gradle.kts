import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "herdr-idea-plugin"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
        id("com.diffplug.spotless") version "8.10.4"
        id("dev.detekt") version "2.0.0-alpha.6"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
