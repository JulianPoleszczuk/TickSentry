plugins {
    java
}

group = "dev.poleszczuk"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    // Kompilujemy przeciw najniższej wspieranej wersji API (1.20.6), dzięki czemu
    // gotowy jar działa zarówno na 1.20.6 jak i na wszystkich nowszych wydaniach Papera.
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")

    // Obie biblioteki zostają poza jarem: sqlite-jdbc pobiera Paper przez `libraries` w plugin.yml,
    // a PlaceholderAPI jest opcjonalną wtyczką na serwerze (soft-depend).
    compileOnly("org.xerial:sqlite-jdbc:3.47.1.0")
    compileOnly("me.clip:placeholderapi:2.11.6")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    // Paper od 1.20.6 wymaga Javy 21 na serwerze, wiec kompilacja do bytecode 21 nic nie ogranicza.
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
