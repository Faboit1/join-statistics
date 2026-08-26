rootProject.name = "join-statistics"

include("common")
include("velocity")
include("bukkit")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://oss.sonatype.org/content/groups/public/") { name = "sonatype" }
        maven("https://repo.extendedclip.com/releases/") { name = "placeholderapi" }
    }
}
