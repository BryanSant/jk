// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import dev.jkbuild.forge.ForgeGitCredentials;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Shared providers for the git-backend parity tests. Every git operation is exercised against both
 * {@link JGitBackend} (always) and {@link GitCliBackend} (only when a usable {@code git} command is
 * present) so the two implementations are held to identical behaviour.
 */
final class GitBackendsTestSupport {
    private GitBackendsTestSupport() {}

    @FunctionalInterface
    interface BackendFactory {
        GitBackend create(Path gitRoot);
    }

    /** JUnit {@code @MethodSource}: (name, factory) for each available backend. */
    static Stream<Arguments> backends() {
        Stream.Builder<Arguments> b = Stream.builder();
        b.add(Arguments.of("jgit", (BackendFactory) gitRoot -> new JGitBackend(gitRoot, new ForgeGitCredentials())));
        GitCliBackend.detect()
                .ifPresent(git -> b.add(Arguments.of(
                        "cli", (BackendFactory) gitRoot -> new GitCliBackend(gitRoot, new ForgeGitCredentials(), git))));
        return b.build();
    }
}
