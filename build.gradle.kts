plugins {
    kotlin("jvm") version "1.9.20"
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("ch.qos.logback:logback-classic:1.5.12")
}

kotlin {
    jvmToolchain(18)
}

tasks {
    shadowJar {
        archiveBaseName.set("webRTCservice")
        archiveVersion.set("")
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
        manifest {
            attributes(
                "Main-Class" to "webRTCservice.MainKt"
            )
        }
    }
}

application {
    mainClass.set("webRTCservice.MainKt")
}