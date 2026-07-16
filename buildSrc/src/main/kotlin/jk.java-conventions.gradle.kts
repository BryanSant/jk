// SPDX-License-Identifier: Apache-2.0

plugins {
    java
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    // Keep in lock-step with jk's own default lint policy (cc.jumpkick.compile.JavacLint),
    // so `gradle build` and `jk build` surface the same javac warnings.
    options.compilerArgs.add("-Xlint:deprecation,unchecked")
}

// Reach the version catalog from a convention plugin without the buildSrc
// classpath hack: read it through VersionCatalogsExtension on the project.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("junit-jupiter").orElseThrow())
    "testImplementation"(libs.findLibrary("assertj-core").orElseThrow())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").orElseThrow())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Isolate tests from the developer's real ~/.jk. JkDirs.home() honours
    // JK_HOME, and everything derived from it (the downloaded global library
    // catalog, cache, credentials, …) follows — so without this a machine
    // that has run `jk library update` would feed its real
    // ~/.jk/cache/libs.global.toml into tests and shadow the bundled layer
    // (e.g. LibrarySearchCommandTest). Point JK_HOME at a throwaway per-module
    // dir to keep tests hermetic.
    environment("JK_HOME", layout.buildDirectory.dir("test-jk-home").get().asFile.absolutePath)
    // Same isolation for the Maven local repository (M2Dirs honours JK_M2_LOCAL):
    // tests that exercise the real fetch pipeline against a mock Maven server would
    // otherwise mirror their stub artifacts into the developer's real ~/.m2 —
    // overwriting genuine jars when a fixture reuses real coordinates (e.g. the
    // injected junit-jupiter test deps) and corrupting every later build on the
    // machine. The env var also reaches any jk subprocess a test forks.
    environment("JK_M2_LOCAL", layout.buildDirectory.dir("test-m2").get().asFile.absolutePath)
    // The embedded HTTP server is on by default (docs/http.md). Tests must not open listening
    // sockets as a side effect: engines spawned by integration tests would race the developer's
    // real engine (and each other, across parallel checkouts) for port 8910. The env var reaches
    // any jk subprocess a test forks; suites that exercise HTTP construct HttpEngineServer (or
    // pass an explicit JkHttpConfig) directly.
    environment("JK_HTTP_ENABLED", "false")
}
