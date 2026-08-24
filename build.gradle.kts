import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm") version "2.1.20"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "nemallone.bworld"
version = "1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        content {
            includeGroup("io.papermc.paper")
            includeGroup("com.mojang")
            includeGroup("net.md-5")
        }
    }
    maven("https://jitpack.io") {
        content { includeGroup("com.github.LeonMangler") }
    }
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") {
        content { includeGroup("me.clip") }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.github.LeonMangler:PremiumVanishAPI:2.9.18-2")
    compileOnly("me.clip:placeholderapi:2.11.6")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

kotlin {
    jvmToolchain(21)
}

val resourceProperties = mapOf("version" to version.toString())
val commitHash = providers.gradleProperty("commitHash")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
    .map { it.take(8) }
    .orElse("dev")

tasks {
    processResources {
        inputs.properties(resourceProperties)
        filesMatching("plugin.yml") {
            expand(resourceProperties)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        archiveVersion.set(commitHash.map { "${project.version}-$it" })
        from(rootProject.file("LICENSE")) {
            into("META-INF")
            rename { "PupsChat-LICENSE.txt" }
        }
        from(rootProject.file("THIRD-PARTY-NOTICES.md")) {
            into("META-INF")
            rename { "THIRD-PARTY-NOTICES.txt" }
        }
        from(rootProject.file("licenses/Apache-2.0.txt")) {
            into("META-INF")
            rename { "LICENSE-Apache-2.0.txt" }
        }
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
