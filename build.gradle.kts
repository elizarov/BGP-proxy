plugins {
    application
    kotlin("multiplatform") version "2.0.21"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

val ktorVersion = "3.0.1"

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
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("BgpProxyKt")
}

tasks.shadowJar {
    archiveBaseName.set("bgp-proxy")
}


