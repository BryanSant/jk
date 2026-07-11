// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.build;

import java.nio.file.Path;
import java.util.List;

/** What a {@link VerbSpec} body sees: its CLI args, the plugin config/project facts, and output. */
public interface VerbExec {

    /** The args after the verb on the jk command line, verbatim. */
    List<String> args();

    dev.jkbuild.model.PluginConfig config();

    ProjectFacts project();

    /** The project directory the verb runs against. */
    Path moduleDir();

    /** Emit one user-facing output line (the client prints these in order). */
    void out(String line);

    /** Progress label (spinner text), same channel as step/packager labels. */
    void label(String text);
}
