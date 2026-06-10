// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk plugin SPI: the stable surface plugins compile against, plus " +
        "the host<->plugin wire protocol codec."

dependencies {
    // model exposes Goal, Phase, GoalListener, Coordinate, Dependency, etc. —
    // the types plugins reference. Zero external deps of its own.
    api(project(":model"))
}
