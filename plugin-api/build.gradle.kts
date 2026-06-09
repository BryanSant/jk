// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk plugin SPI: the stable surface plugins compile against, plus " +
        "the host<->plugin wire protocol codec. Depends on nothing but the JDK."

// Intentionally dependency-free. plugin-api is the contract shared by the host
// and by first- and third-party plugins; keeping it JDK-only is what lets a
// plugin jar stay tiny and lets the codec be bundled anywhere without dragging
// in jk internals. (model is added here once the Command/Goal types move in.)
