plugins {
    java
    application
}

group = "hmcl.patcher"
version = "1.1.0"

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

// The unobfuscated jar; the obfuscated one is produced by the `obfuscate` task below.
tasks.jar {
    archiveFileName.set("hmcl-patcher-dev.jar")
    manifest.attributes(
        "Main-Class" to "hmcl.patcher.Main",
        "Implementation-Title" to "hmcl-patcher",
        "Implementation-Version" to project.version.toString(),
    )
}

val proguardClasspath: Configuration by configurations.creating

dependencies {
    proguardClasspath("com.guardsquare:proguard-base:7.10.0")
}

val obfuscate = tasks.register<JavaExec>("obfuscate") {
    group = "build"
    description = "Obfuscates the patcher with ProGuard."
    dependsOn(tasks.jar)
    classpath = proguardClasspath
    mainClass.set("proguard.ProGuard")
    workingDir = projectDir
    args("@" + project.file("proguard.pro").absolutePath)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.file(project.file("proguard.pro"))
    outputs.file(layout.buildDirectory.file("libs/hmcl-patcher.jar"))
    doFirst {
        layout.buildDirectory.dir("obfuscation").get().asFile.mkdirs()
    }
}

tasks.named("build") {
    dependsOn(obfuscate)
}
