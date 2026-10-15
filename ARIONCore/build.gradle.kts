import java.net.URI
import java.security.MessageDigest

plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "id.arion"
version = "0.2.0-SNAPSHOT"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

// Official release asset; no invented Maven coordinate or moving branch dependency.
val saberJar = layout.buildDirectory.file("dependencies/SaberFactions-4.1.9.jar")
val saberSha256 = "155084547ed93a53e071f13a0a30b251c66f5de13127cfd473b5a877a6f85750"
val prepareSaber by tasks.registering {
    outputs.file(saberJar)
    doLast {
        val target = saberJar.get().asFile
        target.parentFile.mkdirs()
        if (!target.exists()) {
            val temporary = File(target.parentFile, "SaberFactions.download")
            val download = URI("https://github.com/SaberLLC/Saber-Factions/releases/download/4.1.9-STABLE/SaberFactions.jar")
                .toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 60_000 }
            download.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            val actual = MessageDigest.getInstance("SHA-256").digest(temporary.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(actual == saberSha256) { "SaberFactions release SHA-256 mismatch" }
            check(temporary.renameTo(target)) { "Cannot install verified SaberFactions dependency" }
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(target.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == saberSha256) { "SaberFactions dependency SHA-256 mismatch" }
    }
    // Re-check even an existing local artifact before compiling.
    outputs.upToDateWhen { false }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(saberJar).builtBy(prepareSaber))
    implementation("org.xerial:sqlite-jdbc:3.49.1.0") { exclude(group = "org.slf4j") }
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

// Paper's historical SNAPSHOT coordinate can move; validate the exact API used for this milestone.
val compileClasspath = configurations.compileClasspath
val verifyPaper by tasks.registering {
    dependsOn(prepareSaber)
    doLast {
        val paper = compileClasspath.get().incoming.artifacts.artifacts.single {
            val id = it.id.componentIdentifier
            id is org.gradle.api.artifacts.component.ModuleComponentIdentifier &&
                id.group == "io.papermc.paper" && id.module == "paper-api"
        }.file
        val actual = MessageDigest.getInstance("SHA-256").digest(paper.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == "c577b181c11a8674310e56c92a91e31c010b7f04c9bd10b91c3be18374401070") {
            "Paper 1.21.11 API snapshot changed. Review the dependency before updating its checksum."
        }
    }
}
tasks.compileJava { dependsOn(verifyPaper) }
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}
tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}
tasks.test {
    useJUnitPlatform()
    val testTemp = layout.buildDirectory.dir("test-tmp")
    systemProperty("java.io.tmpdir", testTemp.get().asFile.absolutePath)
    doFirst { testTemp.get().asFile.mkdirs() }
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.jar { archiveClassifier.set("plain") }
tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    // Keep Xerial's package names: its native library extraction relies on them.
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.build { dependsOn(tasks.shadowJar) }
tasks.wrapper { gradleVersion = "8.13"; distributionType = Wrapper.DistributionType.BIN }
