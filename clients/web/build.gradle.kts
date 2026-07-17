// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk web dashboard: the resident engine's single-page dashboard (Vue-from-CDN SPA, no " +
        "build step). Pure static assets served at classpath /www/ — no Java main. The engine takes " +
        "this as a runtimeOnly dependency so StaticContent finds /www/* and the jk-engine fat jar " +
        "bundles it; the native CLI never links it (the dashboard is a server concern). The only " +
        "test is the headless fold.js event-folding suite, run via `node --test` when Node is present."
