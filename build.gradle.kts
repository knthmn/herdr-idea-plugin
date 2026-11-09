import org.jetbrains.changelog.Changelog
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("com.diffplug.spotless")
    id("dev.detekt")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors = true
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2.3")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
        val changelog = project.changelog
        changeNotes =
            providers.gradleProperty("version").map { version ->
                with(changelog) {
                    renderItem(
                        (getOrNull(version) ?: getUnreleased())
                            .withHeader(false)
                            .withEmptySections(false),
                        Changelog.OutputType.HTML,
                    )
                }
            }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

changelog {
    repositoryUrl = "https://github.com/knthmn/herdr-idea-plugin"
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktfmt("0.64").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktfmt("0.64").kotlinlangStyle()
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt/detekt.yml"))
}

tasks.test {
    useJUnitPlatform()
}

val glibcVersion = "2.17"

val helperPlatforms =
    mapOf(
        "aarch64-apple-darwin" to "darwin-aarch64",
        "x86_64-apple-darwin" to "darwin-x86_64",
        "aarch64-unknown-linux-gnu" to "linux-aarch64",
        "x86_64-unknown-linux-gnu" to "linux-x86_64",
    )
val helperTargets =
    providers
        .gradleProperty("helperTargets")
        .get()
        .split(",")
        .map(String::trim)
        .filter(String::isNotEmpty)
val cargoDir = layout.buildDirectory.dir("cargo")
val linuxHost = System.getProperty("os.name").startsWith("Linux")

val helperBuilds = helperTargets.map { target ->
    val platform =
        helperPlatforms[target] ?: error("Unknown helper target $target in helperTargets")
    platform to
        tasks.register<Exec>("buildHelper-$platform") {
            val zig = target.endsWith("-linux-gnu") && !linuxHost
            group = "build"
            description = "Builds the herdr-attach helper for $target."
            workingDir = file("helper")
            commandLine(
                "cargo",
                if (zig) "zigbuild" else "build",
                "--release",
                "--locked",
                "--target",
                if (zig) "$target.$glibcVersion" else target,
                "--target-dir",
                cargoDir.get().asFile.path,
            )
            inputs.dir("helper/src")
            inputs.files("helper/Cargo.toml", "helper/Cargo.lock")
            outputs.file(cargoDir.map { it.file("$target/release/herdr-attach") })
        }
}

val testHelper by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Runs the herdr-attach helper tests."
        workingDir = file("helper")
        commandLine("cargo", "test", "--locked", "--target-dir", cargoDir.get().asFile.path)
    }

tasks.check { dependsOn(testHelper) }

tasks.prepareSandbox {
    helperBuilds.forEach { (platform, build) ->
        from(build) { into(pluginName.map { "$it/bin/$platform" }) }
    }
}
