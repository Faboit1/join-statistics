plugins {
    java
    alias(libs.plugins.shadow)
}

description = "JoinStatistics companion — resolves PlaceholderAPI values on backend servers."

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.placeholderapi)
    implementation(project(":common"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar { archiveBaseName.set("JoinStatistics-Companion") }
tasks.named<Jar>("sourcesJar") { archiveBaseName.set("JoinStatistics-Companion") }

tasks.shadowJar {
    archiveBaseName.set("JoinStatistics-Companion")
    archiveClassifier.set("")
    relocate("dev.faboit.joinstats.protocol", "dev.faboit.joinstats.bukkit.libs.protocol")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**")
}

tasks.build { dependsOn(tasks.shadowJar) }
