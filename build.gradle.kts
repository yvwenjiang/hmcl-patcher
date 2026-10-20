plugins {
    java
    application
}

group = "hmcl.patcher"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}

application {
    mainClass.set("hmcl.patcher.Main")
}

tasks.jar {
    archiveFileName.set("hmcl-patcher.jar")
    manifest.attributes(
        "Main-Class" to "hmcl.patcher.Main",
        "Implementation-Title" to "hmcl-patcher",
        "Implementation-Version" to project.version.toString(),
    )
}
