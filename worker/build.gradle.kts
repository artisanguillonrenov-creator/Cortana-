// Paired worker (doc 03 §16-17): runs on a PC/server, executes jobs for the tablet. No business logic:
// it never plans, never calls models, never decides policy — the tablet stays authoritative.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":contracts"))
    testImplementation(libs.junit)
}

/** Self-contained runnable jar: `java -jar cortana-worker.jar serve`. */
val workerJar by tasks.registering(Jar::class) {
    group = "build"
    archiveFileName.set("cortana-worker.jar")
    manifest { attributes("Main-Class" to "io.github.artisanguillonrenov.cortana.worker.MainKt") }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "META-INF/*.kotlin_module")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}
