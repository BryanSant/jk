// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.run.Phase;

import java.nio.file.Path;

/**
 * The context a {@link Plugin} receives during {@link Plugin#register}. Lets
 * the plugin read the project model and contribute {@link Phase}s to the
 * current build's {@link dev.jkbuild.run.Goal}.
 *
 * <p>Kept narrow for now — JDK + model types only. A {@code Services} surface
 * (CAS, HTTP, resolver) will be added once the Host stabilises its service
 * facade.
 */
public interface PluginContext {

    /** The parsed {@code jk.toml} for the project being built. */
    JkBuild project();

    /** The project's root directory (parent of {@code jk.toml}). */
    Path workDir();

    /**
     * Register a {@link Phase} to be included in this build's {@link
     * dev.jkbuild.run.Goal}. The phase may declare {@code requires} on
     * first-party phase names (e.g. {@code "assemble-classes"}) to position
     * itself in the DAG.
     */
    void contribute(Phase phase);
}
