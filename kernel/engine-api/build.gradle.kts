// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk engine API: the client<->engine wire contract — protocol codec, engine paths/" +
        "transport, and the front-end-safe build DTOs/listeners (slim-client Stage 5). The thin " +
        "surface a front-end links to talk to the engine; the engine implementation lives in :engine."

dependencies {
    // The DTOs speak the jk-api vocabulary (Pipeline/Step/PipelineListener, JkBuild, TestSummary).
    api(project(":jk-api"))
    implementation(project(":support"))
    // CachePruneScheduler consults JkCacheConfig (~/.jk/config.toml [cache]).
    api(project(":core"))
    // EngineProtocol encodes/decodes with the shared Jsonl codec.
    api(project(":plugin-api"))
}
