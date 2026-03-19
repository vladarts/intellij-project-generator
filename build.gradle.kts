plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.github.vladarts"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
    }
    implementation("org.yaml:snakeyaml:1.33")
    implementation("com.google.code.gson:gson:2.10.1")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.vladarts.idegen"
        name = "Project Generator"
        version = "0.1.0"
        ideaVersion {
            sinceBuild = "241" // 2024.1
            untilBuild = provider { null } // no upper bound
        }
    }
}
