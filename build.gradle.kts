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
    // Built against the oldest supported API (1.16.5) so the finished jar runs on
    // everything from 1.16.5 up to the newest Paper release.
    compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")

    // Both libraries stay out of the jar: sqlite-jdbc is fetched by Paper through the
    // `libraries` entry in plugin.yml, and PlaceholderAPI is an optional server plugin.
    compileOnly("org.xerial:sqlite-jdbc:3.47.1.0")
    compileOnly("me.clip:placeholderapi:2.11.6")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The API on the test classpath as well, so the classes that have to touch Bukkit can be
    // tested too. No MockBukkit: HandlerList and RegisteredListener are ordinary Java objects
    // that need no running server, and a mock server tied to one Minecraft version would sit
    // awkwardly next to a plugin compiled against 1.16.
    testImplementation("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    // Bytecode 11 is the lowest that still covers Minecraft 1.16 servers (Paper recommends
    // Java 11 there) while running fine on the Java 21 required by 1.20.5+. Going lower would
    // mean giving up java.net.http and the collection factories for very little reach.
    options.release = 11
    options.encoding = "UTF-8"
}

// The test sources may use modern syntax - they never ship inside the plugin jar.
tasks.compileTestJava {
    options.release = 21
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
