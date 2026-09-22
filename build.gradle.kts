plugins {
    id("java")
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.ripple"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.5")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        instrumentationTools()
    }
    testImplementation(kotlin("test"))
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        id = "com.ripple.plugin"
        name = "Ripple"
        version = project.version.toString()
        description = "In-editor execution-flow and state time machine. Trace a method, see runtime values inline."
        vendor {
            name = "42 Abu Dhabi x JetBrains Hackathon Team"
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
