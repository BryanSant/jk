// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk-api: the stable front-end/plugin contract — Goal/Phase scheduler SPI, " +
        "Dependency/Coordinate model, GoalListener + command (CliCommand/Invocation) SPI. " +
        "Zero external dependencies — JDK + Lombok + JSpecify only."

// This module IS jk's public API surface ("jk-api"): the impl-free contract that
// plugins, third-party tools, and alternative front-ends (IDE/web/CI) compile
// against. The contract leaf is the {plugin-api, model} PAIR: plugin-api sits below
// (it holds jk's promised JDK 17 project floor — its classes ride the user's test
// JVM — and owns PluginConfig); model layers the rest of the contract on top at the
// engine's language level. Keeping both JDK-only means a consumer needs no jk
// internal classpath to get Goal, Phase, Coordinate, the command SPI, etc. A
// front-end that additionally *drives* builds depends on :engine for the
// BuildService facade (exactly as the CLI does). Do not add project() dependencies
// here beyond :plugin-api, and no external dependencies in either module.

dependencies {
    api(project(":plugin-api"))
}
