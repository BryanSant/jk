// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.host;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.plugin.PluginContext;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.Phase;

import java.nio.file.Path;

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

    HostPluginContext(JkBuild project, Path workDir, Goal.Builder goalBuilder) {
        this.project     = project;
        this.workDir     = workDir;
        this.goalBuilder = goalBuilder;
    }

    @Override
    public JkBuild project() {
        return project;
    }

    @Override
    public Path workDir() {
        return workDir;
    }

    @Override
    public void contribute(Phase phase) {
        goalBuilder.addPhase(phase);
    }
}
