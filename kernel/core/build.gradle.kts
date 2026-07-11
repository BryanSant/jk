// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk core foundations: TOML config parser, lockfile, layout, library catalog, deny policy"

dependencies {
    api(project(":model"))
    api(libs.tomlj)
    // ComparableVersion is the Maven-canonical version comparator (e.g.
    // `1.0-alpha < 1.0-rc < 1.0 < 1.0-sp1`). Used by Versions.compare, rehomed here from
    // :resolver for the slim client (tree/why/library/jdk-list are offline client verbs).
    implementation(libs.maven.artifact)
}

// The spring-boot plugin manifest's single source of truth is plugins/spring-boot/jk-plugin.toml
// (the blueprint file third parties copy). Bake it in as the built-in registry resource so the
// engine ships it without a second, drift-prone copy in this module's resources.
tasks.processResources {
    from(rootProject.file("plugins/spring-boot/jk-plugin.toml")) {
        into("dev/jkbuild/plugin/manifest")
        rename { "spring-boot.jk-plugin.toml" }
    }
    // Scaffold templates ride next to the manifest (registry resource dir <id>/scaffold/...).
    from(rootProject.file("plugins/spring-boot/scaffold")) {
        into("dev/jkbuild/plugin/manifest/spring-boot/scaffold")
    }
    from(rootProject.file("plugins/android/jk-plugin.toml")) {
        into("dev/jkbuild/plugin/manifest")
        rename { "android.jk-plugin.toml" }
    }
}
