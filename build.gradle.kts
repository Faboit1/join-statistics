plugins {
    java
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "dev.faboit.joinstats"
    // CI overrides this so a tagged release is stamped with its tag and a rolling dev build is
    // stamped with its commit. Local builds use the value in gradle.properties.
    version = System.getenv("JOINSTATS_VERSION")?.takeIf(String::isNotBlank)
        ?: property("pluginVersion") as String
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = "UTF-8"
    }
}
