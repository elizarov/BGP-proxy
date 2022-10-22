plugins {
    application
    kotlin("multiplatform") version "1.7.20"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

val ktorVersion = "2.1.2"

repositories {
    mavenCentral()
}

kotlin {
    targets {
        jvm { withJava() }
        linuxX64("linux") {
            binaries {
                executable("bgpProxy", listOf(RELEASE)) {
                    baseName = "bgp-proxy"
                }
            }
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.ktor:ktor-network:$ktorVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
            }
        }
    }

    sourceSets.all {
        languageSettings.apply {
            optIn("kotlin.RequiresOptIn")
            optIn("kotlin.ExperimentalUnsignedTypes")
            optIn("kotlin.time.ExperimentalTime")
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("BgpProxyKt")
}

tasks.shadowJar {
    archiveBaseName.set("bgp-proxy")
}


