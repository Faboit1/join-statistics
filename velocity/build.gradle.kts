plugins {
    java
    alias(libs.plugins.shadow)
}

description = "JoinStatistics — the Velocity proxy plugin."

dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)

    // Shipped inside Velocity itself; declared explicitly so a transitive bump cannot
    // silently remove them from our compile classpath.
    compileOnly(libs.caffeine)
    compileOnly(libs.gson)

    implementation(project(":common"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.hikari)
    implementation(libs.geoip2)

    // Velocity supplies these at runtime; tests need them on the classpath to exercise the
    // config, logging and event types the plugin is written against.
    testImplementation(libs.velocity.api)
    testImplementation(libs.caffeine)
    testImplementation(libs.gson)
}

val generatedSources = layout.buildDirectory.dir("generated/sources/buildconstants/java/main")

val generateBuildConstants by tasks.registering {
    val outputDir = generatedSources
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    outputs.dir(outputDir)
    doLast {
        val pkgDir = outputDir.get().asFile.resolve("dev/faboit/joinstats/velocity")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildConstants.java").writeText(
            """
            package dev.faboit.joinstats.velocity;

            /** Generated at build time — do not edit. */
            public final class BuildConstants {
                public static final String ID = "joinstatistics";
                public static final String NAME = "JoinStatistics";
                public static final String VERSION = "$pluginVersion";

                private BuildConstants() {
                }
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets.main {
    java.srcDir(generatedSources)
}

tasks.compileJava { dependsOn(generateBuildConstants) }
tasks.named("sourcesJar") { dependsOn(generateBuildConstants) }

// velocity-plugin.json is generated from the @Plugin annotation by the API's annotation
// processor, so there is no descriptor to template here.

tasks.jar { archiveBaseName.set("JoinStatistics-Velocity") }
tasks.named<Jar>("sourcesJar") { archiveBaseName.set("JoinStatistics-Velocity") }

tasks.shadowJar {
    archiveBaseName.set("JoinStatistics-Velocity")
    archiveClassifier.set("")

    // Velocity already exposes Gson, slf4j, Adventure, Configurate and Guava.
    dependencies {
        exclude(dependency("com.google.code.gson:gson"))
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("com.google.guava:.*"))
        exclude(dependency("org.checkerframework:.*"))
        exclude(dependency("com.google.errorprone:.*"))
        exclude(dependency("com.google.j2objc:.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }

    // sqlite-jdbc bundles a native library for twenty platforms, which is 20 MB and the
    // overwhelming bulk of this jar. Most Minecraft panels cap uploads at 10 MB (PHP's default),
    // so shipping all of them makes the plugin impossible to install through the very interface
    // most operators have. Keep the platforms a Velocity proxy is actually run on — Linux and
    // Alpine on Intel and ARM, plus Windows and macOS for local testing — and drop the rest.
    // Database.java turns the resulting load failure into an explicit, actionable message.
    listOf(
        "FreeBSD/**",
        "Linux/ppc64/**",
        "Linux/riscv64/**",
        "Linux/x86/**",
        "Linux/arm/**",
        "Linux/armv6/**",
        "Linux/armv7/**",
        "Linux-Musl/x86/**",
        "Windows/x86/**",
        "Windows/armv7/**",
        "Windows/aarch64/**",
    ).forEach { exclude("org/sqlite/native/$it") }

    // org.sqlite is deliberately NOT relocated: the driver resolves its bundled
    // native libraries through hard-coded resource paths.
    relocate("com.zaxxer.hikari", "dev.faboit.joinstats.libs.hikari")
    relocate("com.maxmind", "dev.faboit.joinstats.libs.maxmind")
    relocate("com.fasterxml.jackson", "dev.faboit.joinstats.libs.jackson")

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
