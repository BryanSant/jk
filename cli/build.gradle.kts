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

    testImplementation(testFixtures(project(":supply-chain")))
}

application {
    mainClass.set("dev.buildjk.cli.Jk")
    applicationName = "jk"
}

graalvmNative {
    binaries.named("main") {
        imageName.set("jk")
        mainClass.set("dev.buildjk.cli.Jk")
    }
}
