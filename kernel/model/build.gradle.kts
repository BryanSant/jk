// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk domain model: Goal/Phase scheduler types, Dependency/Coordinate model, " +
        "GoalListener SPI. Zero external dependencies — JDK + Lombok + JSpecify only."

// Intentionally dependency-free. This module is the stable surface plugin-api
// and third-party plugins compile against — keeping it JDK-only means a plugin
// jar needs no jk internal classpath to get Goal, Phase, Coordinate, etc.
