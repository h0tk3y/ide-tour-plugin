plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.4.0"
}

group = "com.gradle.idetour"
version = "0.2.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { "" }
        }
    }
    buildSearchableOptions = false
}

tasks {
    runIde {
        // When a dynamic-unload attempt fails, the IDE will dump a heap snapshot
        // into the sandbox's log directory. Analyse it to find references pinning
        // the plugin classloader.
        jvmArgs("-Dide.plugins.snapshot.on.unload.fail=true")
    }
}
