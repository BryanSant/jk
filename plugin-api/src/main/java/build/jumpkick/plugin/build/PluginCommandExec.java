// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import java.nio.file.Path;
import java.util.List;

/** What a {@link PluginCommandSpec} body sees: its CLI args, the plugin config/project facts, and output. */
public interface PluginCommandExec {

    /** The args after the command on the jk command line, verbatim. */
    List<String> args();

    build.jumpkick.plugin.PluginConfig config();

    ProjectFacts project();

    /** The project directory the command runs against. */
    Path moduleDir();

    /**
     * A declared {@code [[contribute.step-dependency]]} artifact, resolved engine-side — commands
     * get the same tool artifacts steps do (adb from an SDK component, a bundled tool jar).
     */
    java.util.Optional<Path> extra(String name);

    /** As {@link #extra} but required. */
    default Path requireExtra(String name) {
        return extra(name).orElseThrow(() -> new IllegalStateException("tool artifact not provided: " + name
                + " — declare it as a [[contribute.step-dependency]]"));
    }

    /**
     * The built main artifact (the packager's, under its declared extension — an APK), or empty
     * when not built yet. Deploy-style commands consume this instead of learning jk's layout.
     */
    java.util.Optional<Path> mainArtifact();

    /** Emit one user-facing output line (the client prints these in order). */
    void out(String line);

    /** Progress label (spinner text), same channel as step/packager labels. */
    void label(String text);
}
