plugins {
    application
    kotlin("jvm") version "1.6.21"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

val ktorVersion = "2.0.2"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

dependencies {
    implementation("io.ktor:ktor-network:$ktorVersion")
}

sourceSets {
    val main by getting {
        java.srcDir("src")
    }
    val test by getting {
        java.srcDir("test")
    }
}

application {
    mainClass.set("BgpProxyKt")
}

tasks.shadowJar {
    archiveBaseName.set("bgp-proxy")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += listOf(
        "-opt-in=kotlin.RequiresOptIn",
        "-opt-in=kotlin.ExperimentalUnsignedTypes",
        "-opt-in=kotlin.time.ExperimentalTime",
    )
}

