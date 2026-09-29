plugins {
    `java-library`
    `maven-publish`
}

group = "io.blipit"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    api("io.sentry:sentry:8.58.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "blipit"
            pom {
                name.set("blipit")
                description.set("Blipit error monitoring for Java and Kotlin. Know your app broke before your users tell you.")
                url.set("https://docs.blipit.io/java")
                licenses {
                    license {
                        name.set("MIT")
                    }
                }
            }
        }
    }
}
