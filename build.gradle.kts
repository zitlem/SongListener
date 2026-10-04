plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.compose") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("org.jetbrains.compose") version "1.10.2"
}

group = "songlistener"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

val ktorVersion = "3.4.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.10.0-alpha05")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets-jvm:$ktorVersion")
    implementation("io.socket:socket.io-client:2.1.1")
    // Reads STT's session database (a copy of it) when importing a past service.
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock-jvm:$ktorVersion")
}

tasks.test {
    systemProperty("java.awt.headless", "true")
}

compose.desktop {
    application {
        mainClass = "songlistener.ui.MainKt"
        nativeDistributions {
            packageName = "SongListener"
            // macOS bundles need a first version number above zero.
            packageVersion = "1.0.0"
        }
    }
}

tasks.register<JavaExec>("report") {
    group = "application"
    description = "Writes ~/.songlistener/reports/<day>.html for every day logged."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("songlistener.io.ReportMainKt")
    listOf("logs", "out").forEach { name -> project.findProperty(name)?.let { systemProperty("songlistener.$name", it.toString()) } }
}

tasks.register<JavaExec>("replay") {
    group = "application"
    description = "Leave-one-out replay of every saved song recording, e.g. ./gradlew replay -PsuggestScore=0.75"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("songlistener.io.ReplayMainKt")
    listOf("suggestScore", "suggestMargin", "suggestStreak", "queryFrames", "musicDipToleranceSeconds", "tempoStretches", "feature", "framesPerFeature", "transpose", "steadyFrames", "steadyScore", "steadyMargin", "wrongSlideMargin", "origin").forEach { name ->
        project.findProperty(name)?.let { systemProperty("songlistener.$name", it.toString()) }
    }
}

tasks.register<JavaExec>("importService") {
    group = "application"
    description = "Cuts song recordings out of STT service backups: ./gradlew importService -Pwav=<file or folder> [-Pdb=<file or folder>] [-PcpLogs=<dir>] [-PplayLog=<file>] [-Psource=cp|songlistener|playlog] [-Pmusic=audio] [-PseedTakes=true] [-PdryRun=true]"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("songlistener.io.ImportMainKt")
    maxHeapSize = "2g"
    listOf("wav", "db", "cpLogs", "playLog", "songCache", "source", "music", "seedTakes", "includeUnusual", "includeAll", "dryRun").forEach { name ->
        project.findProperty(name)?.let { systemProperty("songlistener.$name", it.toString()) }
    }
}
