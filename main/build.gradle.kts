plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
}

base {
    archivesName = "lavasrc"
}

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api("com.github.topi314.lavasearch:lavasearch:1.0.0")
    api("com.github.topi314.lavalyrics:lavalyrics:1.0.0")
    compileOnly("dev.arbjerg:lavaplayer:2.0.4")
    compileOnly("com.github.lavalink-devs.youtube-source:common:1.0.5")
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("commons-io:commons-io:2.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("org.jetbrains.kotlin:kotlin-annotations-jvm:1.9.0")
    implementation("com.auth0:java-jwt:4.4.0")
    compileOnly("org.slf4j:slf4j-api:2.0.7")

    lyricsDependency("protocol")
    lyricsDependency("client")

    implementation("se.michaelthelin.spotify:spotify-web-api-java:8.4.1")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.google.guava:guava:33.2.1-jre")
    implementation("org.jooq:jooq:3.19.8")

    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    testCompileOnly("org.projectlombok:lombok:1.18.34")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.34")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            pom {
                artifactId = base.archivesName.get()
                from(components["java"])
            }
        }
    }
}

kotlin {
    jvmToolchain(11)
}


fun DependencyHandlerScope.lyricsDependency(module: String) {
    implementation("dev.schlaubi.lyrics", "$module-jvm", "2.2.2") {
        isTransitive = false
    }
}