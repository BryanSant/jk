// SPDX-License-Identifier: Apache-2.0

// Root project. Conventions live in buildSrc/ and are applied per module.
// Library/plugin pins live in gradle/libs.versions.toml.

tasks.wrapper {
    gradleVersion = "9.5.1"
    distributionType = Wrapper.DistributionType.BIN
}

// The shippable native-dist layout (docs/engine.md "Two artifacts"): the size-tuned native jk
// client next to the engine's fat jar. The engine is a JVM app, never a native image — the
// installed client spawns it on the jk-managed JDK as `java -cp ~/.jk/lib/jk-engine-<version>.jar
// EngineMain`. dist/lib mirrors ~/.jk/lib so the installer copies it straight across.
val dist by tasks.registering(Sync::class) {
    description = "Assembles build/dist: the native jk client + lib/jk-engine-<version>.jar"
    group = "distribution"
    dependsOn(":cli:nativeCompile")
    from(project(":cli").layout.buildDirectory.dir("native/nativeCompile")) { include("jk", "jk.exe") }
    from(project(":cli-engine").tasks.named("shadowJar")) { into("lib") }
    into(layout.buildDirectory.dir("dist"))
}
