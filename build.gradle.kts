// SPDX-License-Identifier: Apache-2.0

// Root project. Conventions live in buildSrc/ and are applied per module.
// Library/plugin pins live in gradle/libs.versions.toml.

tasks.wrapper {
    gradleVersion = "9.5.1"
    distributionType = Wrapper.DistributionType.BIN
}

// The shippable native-dist layout (docs/engine.md "Two artifacts"): the size-tuned native jk
// client next to the engine's plain-jar directory. The engine is a JVM app, never a native image —
// the client spawns it on the jk-managed JDK as `java -cp 'libexec/jk-engine/*' EngineMain`.
val dist by tasks.registering(Sync::class) {
    description = "Assembles build/dist: the native jk client + libexec/jk-engine/*.jar"
    group = "distribution"
    dependsOn(":cli:nativeCompile", ":cli-engine:installEngineLibs")
    from(project(":cli").layout.buildDirectory.dir("native/nativeCompile")) { include("jk", "jk.exe") }
    from(project(":cli-engine").layout.buildDirectory.dir("libexec")) { into("libexec") }
    into(layout.buildDirectory.dir("dist"))
}
