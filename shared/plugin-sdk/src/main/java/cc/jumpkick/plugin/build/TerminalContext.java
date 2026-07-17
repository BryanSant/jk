// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import cc.jumpkick.plugin.PluginConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The shared read surface behind the terminal-goal capabilities ({@link RunExtension}, {@link
 * ImageExtension}, {@link PublishExtension}): a terminal goal consumes the finished module — the
 * built main artifact and the runtime closure — rather than contributing a step to the build.
 *
 * <p>Terminal-goal <em>execution</em> (the {@code run}/{@code image}/{@code publish} DAG nodes and
 * their worker ops) is wired in the extension-remodel plan's Stream 6; today these interfaces pin
 * the authoring contract so the full phase surface is visible and third-party plugins can compile
 * against it.
 */
public interface TerminalContext {

    /** The plugin's validated config table. */
    PluginConfig config();

    /** Read-only project facts (coords, resolved main, capability flags). */
    ProjectFacts project();

    /** The module's project directory. */
    Path moduleDir();

    /** The built main artifact under its declared extension, or empty when not built. */
    Optional<Path> mainArtifact();

    /** The lock-ordered production RUNTIME entries (the runtime closure). */
    List<PackageIo.RuntimeEntry> runtimeEntries();

    /** The JDK this build runs on — for tool forks. */
    Path javaHome();

    /** Progress label surfaced in the build UI. */
    void label(String text);
}
