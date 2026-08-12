plugins { java }

group = "kr.hyuni.marketplay"
version = "1.0.0"

repositories { maven("https://repo.papermc.io/repository/maven-public/") }

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly(files("../plugins/FastAsyncWorldEdit-Paper-2.15.3.jar"))
    compileOnly(files("../plugins/worldguard-bukkit-7.0.14-dist.jar"))
    compileOnly(files("../plugins/Citizens-2.0.40-b3957.jar"))
    implementation("org.xerial:sqlite-jdbc:3.53.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // MarketPlayHelp creates Adventure components in pure unit tests. Paper exposes
    // this API at runtime on a server, but tests need the API explicitly.
    testImplementation("net.kyori:adventure-api:4.24.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test { useJUnitPlatform() }
tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.register<Copy>("deployToServer") {
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap { it.archiveFile })
    into(layout.projectDirectory.dir("../plugins"))
    rename { "MarketPlay.jar" }
}
