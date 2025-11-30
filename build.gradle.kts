plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20"
    id("com.gradleup.shadow") version "9.2.2"
    id("application")
}

group = "webRTCservice"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.corundumstudio.socketio:netty-socketio:2.0.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("ch.qos.logback:logback-classic:1.5.12")
}

kotlin {
    jvmToolchain(18)
}

tasks {
    shadowJar {
        archiveBaseName.set("upload_server")
        archiveVersion.set("")
        archiveClassifier.set("")
        mergeServiceFiles()
        manifest {
            attributes(
                "Main-Class" to "MainKt"
            )
        }
    }
}

kotlin {
    jvmToolchain(18)
}

application {
    mainClass.set("server.MainKt")
}
