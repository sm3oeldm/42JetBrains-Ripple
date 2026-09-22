plugins {
    id("java")
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "ad42.devrescue"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Target IDE — Community is free + enough for demo
        intellijIdeaCommunity("2024.3.5")
        // Bundled Java plugin so Editor/PSI APIs resolve in runIde sandbox
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        instrumentationTools()
    }
    testImplementation(kotlin("test"))
}

intellijPlatform {
    buildSearchableOptions = false
    // 2024.3.5 = stable + fast download for hackathon. Supports IntelliJ IDEA Community.
    pluginConfiguration {
        id = "ad42.devrescue"
        name = "DevRescue - Error Explainer + Health Guardian"
        version = project.version.toString()
        description = "Help the Developer: explain stacktraces in plain English and guard code health. Offline-first, LLM-enhanced."
        vendor {
            name = "42 Abu Dhabi"
            email = "team@42abudhabi.ae"
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
    }
}
