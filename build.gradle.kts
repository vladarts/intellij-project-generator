plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.github.vladarts"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.3")
        bundledPlugin("com.intellij.platform.vcs")
        bundledModule("intellij.platform.vcs")
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
    implementation("org.yaml:snakeyaml:1.33")
    implementation("com.google.code.gson:gson:2.10.1")
}

intellijPlatform {
    pluginVerification {
        ides {
            current()
        }
    }
    pluginConfiguration {
        id = "com.github.vladarts.idegen"
        name = "Project Generator"
        version = "0.1.0"
        ideaVersion {
            sinceBuild = "263" // 2026.3 (Java 25)
            untilBuild = provider { null } // no upper bound
        }
    }
}
