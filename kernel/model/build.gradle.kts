// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk-api: the stable front-end/plugin contract — Goal/Phase scheduler SPI, " +
        "Dependency/Coordinate model, GoalListener + command (CliCommand/Invocation) SPI. " +
        "Zero external dependencies — JDK + Lombok + JSpecify only."

// This module IS jk's public API surface ("jk-api"): the impl-free contract that
// plugins, third-party tools, and alternative front-ends (IDE/web/CI) compile
// against. Intentionally dependency-free and dependency-graph-leaf — keeping it
// JDK-only means a consumer needs no jk internal classpath to get Goal, Phase,
// Coordinate, the command SPI, etc. A front-end that additionally *drives* builds
// depends on :engine for the BuildService facade (exactly as the CLI does); that
// facade is engine-coupled by design and is the one half of the contract not in
// this leaf module. Do not add project() or external dependencies here.
