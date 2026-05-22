// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    application
    alias(libs.plugins.graalvm.native)
}

description = "jk command-line entrypoint"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":resolver"))
    implementation(project(":toolchain"))
    implementation(project(":engine"))
    implementation(project(":supply-chain"))
    implementation(project(":image"))
    implementation(project(":compat"))

    implementation(libs.picocli)
    // picocli-codegen runs at compile time to emit
    // META-INF/native-image/picocli-generated/<project>/reflection-config.json
    // for every @Command-annotated class. Without this the native-image build
    // strips the annotation metadata picocli needs at runtime, and the binary
    // throws "AutoHelpMixin is not a command" on first call.
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")

    testImplementation(testFixtures(project(":supply-chain")))
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Aproject=dev.buildjk.cli")
}

application {
    mainClass.set("dev.buildjk.cli.Jk")
    applicationName = "jk"
}

graalvmNative {
    binaries.named("main") {
        imageName.set("jk")
        mainClass.set("dev.buildjk.cli.Jk")
        // The plugin's "main" binary is supposed to default to executable,
        // but the 0.10.4 / GraalVM 25 combination defaults to shared library
        // on this host. Force the executable mode explicitly.
        sharedLibrary.set(false)
    }
}

// kotlin-compiler-embeddable (pulled in via :engine for the KotlincDriver)
// ships shaded JLine classes plus META-INF/native-image/org.jline/<mod>/
// native-image.properties files that reference reflection-config.json /
// resource-config.json — but those JSON files aren't shaded in. Without
// them, native-image bails. jk never invokes JLine, so we provide empty
// stubs under cli/src/main/resources/META-INF/native-image/org.jline/.
