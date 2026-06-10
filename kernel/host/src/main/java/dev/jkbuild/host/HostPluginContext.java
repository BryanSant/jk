// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.host;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.plugin.PluginContext;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.Phase;

import java.nio.file.Path;
import java.util.Map;

/**
 * Host-side implementation of {@link PluginContext}. Backed by the project's
 * parsed {@link JkBuild} and the in-progress {@link Goal.Builder} — phases
 * contributed via {@link #contribute} are added directly to the builder and
 * become part of the final Goal DAG.
 */
final class HostPluginContext implements PluginContext {

    private final JkBuild project;
    private final Path workDir;
    private final Goal.Builder goalBuilder;
    private final Map<String, Object> config;

    HostPluginContext(JkBuild project, Path workDir, Goal.Builder goalBuilder,
                     Map<String, Object> config) {
        this.project     = project;
        this.workDir     = workDir;
        this.goalBuilder = goalBuilder;
        this.config      = config;
    }

    @Override public JkBuild project() { return project; }
    @Override public Path workDir()    { return workDir; }
    @Override public Map<String, Object> config() { return config; }

    @Override
    public void contribute(Phase phase) {
        goalBuilder.addPhase(phase);
    }
}
