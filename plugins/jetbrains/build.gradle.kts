import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.jdubois.bootui"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")

    intellijPlatform {
        intellijIdea("2025.3")
        bundledModule("intellij.xml.psi")
        bundledPlugin("org.intellij.groovy")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            current()
        }
    }
}
