plugins {
    kotlin("multiplatform") version "2.0.21"
}

val ktorVersion = "3.0.1"

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        withJava()
    }
    linuxX64 {
        binaries {
            executable("bgpProxy", listOf(RELEASE)) {
                baseName = "bgp-proxy"
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


